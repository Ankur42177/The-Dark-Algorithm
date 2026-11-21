package com.pulse.content.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.pulse.content.auth.TokenService.Tokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Login / refresh / logout / me, and the public JWKS endpoint.
 *
 * <p>Tokens travel in <b>cookies</b>, not in JavaScript-readable storage:
 * <ul>
 *   <li>{@code HttpOnly} — JavaScript can't read them, so an XSS bug can't steal them.</li>
 *   <li>{@code Secure} — only sent over HTTPS (browsers treat http://localhost as secure).</li>
 *   <li>{@code SameSite=Strict} — not sent on requests started by other sites (CSRF defence #1).</li>
 *   <li>Refresh cookie {@code Path=/api/v1/auth} — only sent to the auth endpoints, nowhere else.</li>
 * </ul>
 */
@RestController
@Tag(name = "Auth", description = "Admin login with JWT access tokens and rotating refresh tokens")
public class AuthController {

    static final String ACCESS_COOKIE = "pulse_at";
    static final String REFRESH_COOKIE = "pulse_rt";

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record Me(String username, String displayName, Instant accessExpiresAt) {
    }

    private final AdminUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginThrottle throttle;
    private final AuthProperties props;
    private final JWKSet publicJwks;
    /** Real BCrypt hash of a random string: burns the same CPU time when the username doesn't exist. */
    private final String dummyHash;

    public AuthController(AdminUserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                          LoginThrottle throttle, AuthProperties props, JWKSet publicJwkSet) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.throttle = throttle;
        this.props = props;
        this.publicJwks = publicJwkSet;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    @PostMapping("/api/v1/auth/login")
    @Operation(summary = "Log in; sets HttpOnly access + refresh cookies")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        String ip = http.getRemoteAddr();
        if (throttle.isBlocked(ip)) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many failed logins. Try again later.");
            p.setTitle("Too many attempts");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "900").body(p);
        }
        var user = users.findByUsername(req.username());
        // ALWAYS run the (slow) hash check, even for unknown users: equal timing means attackers
        // can't discover valid usernames by measuring response time.
        String hash = user.map(AdminUserRepository.AdminUser::passwordHash).orElse(dummyHash);
        boolean ok = passwordEncoder.matches(req.password(), hash) && user.isPresent();
        if (!ok) {
            throttle.recordFailure(ip);
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
            p.setTitle("Login failed");   // never say WHICH one was wrong
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(p);
        }
        throttle.reset(ip);
        touchCsrf(http);
        return withCookies(tokens.login(user.get()), res);
    }

    @PostMapping("/api/v1/auth/refresh")
    @Operation(summary = "Rotate the refresh token; new access token")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh,
                                     HttpServletRequest http, HttpServletResponse res) {
        if (refresh == null) {
            return unauthorized("No refresh token");
        }
        try {
            touchCsrf(http);
            return withCookies(tokens.refresh(refresh), res);
        } catch (TokenService.InvalidRefreshTokenException e) {
            return clearCookies(HttpStatus.UNAUTHORIZED, e.getMessage(), res);
        }
    }

    @PostMapping("/api/v1/auth/logout")
    @Operation(summary = "Revoke the session and clear cookies")
    public ResponseEntity<?> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh, HttpServletResponse res) {
        tokens.logout(refresh);
        return clearCookies(HttpStatus.NO_CONTENT, null, res);
    }

    @GetMapping("/api/v1/auth/me")
    @Operation(summary = "Who am I? (also issues the XSRF-TOKEN cookie)")
    public Me me(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        touchCsrf(http);
        return new Me(jwt.getSubject(), jwt.getClaimAsString("name"), jwt.getExpiresAt());
    }

    /** Public keys for verifying our JWTs (used by the api-gateway in Phase 5). Never contains the private key. */
    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "JSON Web Key Set (public keys)")
    public Map<String, Object> jwks() {
        return publicJwks.toJSONObject();
    }

    // ------------------------------------------------------------------ helpers

    /*
     * Cookies are ADDED straight to the servlet response. If we put Set-Cookie on the ResponseEntity instead,
     * Spring copies those headers onto the response with "replace" semantics and silently drops the
     * XSRF-TOKEN cookie that the CSRF filter added earlier. (Bug caught by AuthAndAdminIT.)
     */
    private ResponseEntity<?> withCookies(Tokens t, HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, t.accessToken(), "/", props.accessTtl()).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, t.refreshToken(), "/api/v1/auth", props.refreshTtl()).toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new Me(t.user().username(), t.user().displayName(), t.accessExpiresAt()));
    }

    private ResponseEntity<?> clearCookies(HttpStatus status, String detail, HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, "", "/", Duration.ZERO).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", "/api/v1/auth", Duration.ZERO).toString());
        var b = ResponseEntity.status(status);
        return detail == null ? b.build() : b.body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path(path).maxAge(maxAge).build();
    }

    private ResponseEntity<?> unauthorized(String detail) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail));
    }

    /** Loading the CSRF token makes Spring Security write the XSRF-TOKEN cookie the admin UI echoes back. */
    private static void touchCsrf(HttpServletRequest http) {
        if (http.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken t) {
            t.getToken();
        }
    }

}
