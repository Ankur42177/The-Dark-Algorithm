package com.pulse.content.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration: WHO may call WHAT.
 *
 * <p>Every request passes through a chain of security filters before reaching a controller:
 * CORS → CSRF → bearer-token (JWT) authentication → authorization rules → your code.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, BearerTokenResolver tokenResolver) throws Exception {
        http
            // No server-side HTTP sessions: every request carries its JWT (stateless → any replica can serve it).
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            // CSRF for cookie-authenticated, state-changing requests. spa() = XSRF-TOKEN cookie readable by
            // JavaScript + X-XSRF-TOKEN header expected back ("double-submit cookie").
            .csrf(csrf -> csrf.spa().ignoringRequestMatchers(
                    "/api/v1/auth/login",          // no session exists yet (and SameSite + throttle protect it)
                    "/api/v1/auth/refresh",        // refresh cookie is SameSite=Strict + path-scoped
                    "/api/v1/posts/*/view"))       // anonymous beacon, nothing to protect
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/auth/me").authenticated()
                    .anyRequest().permitAll())     // public API, docs, JWKS
            .oauth2ResourceServer(rs -> rs
                    .bearerTokenResolver(tokenResolver)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromClaim()))
                    .authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED, "Authentication required")))
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) ->
                    problem(res, HttpStatus.FORBIDDEN, "Forbidden (missing role or CSRF token)")))
            // Runs AFTER the CSRF check: turns the pulse_at cookie into an Authorization header.
            .addFilterAfter(new CookieToBearerFilter(), CsrfFilter.class);
        return http.build();
    }

    /**
     * Only the Authorization HEADER counts as a bearer token. Why not read the cookie here too? Spring
     * Security skips CSRF for "bearer token requests" (an attacker's site can't add headers, so header
     * tokens are CSRF-safe). If the cookie counted as a bearer token, cookie-authenticated browser requests
     * would ALSO skip CSRF — exactly the requests CSRF exists for. (Bug caught by AuthAndAdminIT.)
     * The cookie is handled by {@link CookieToBearerFilter}, which runs after the CSRF check.
     */
    @Bean
    BearerTokenResolver tokenResolver() {
        return new DefaultBearerTokenResolver();
    }

    /** "roles": ["ADMIN"] in the JWT → authority ROLE_ADMIN → hasRole("ADMIN") passes. */
    private static JwtAuthenticationConverter rolesFromClaim() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(roles);
        return c;
    }

    /**
     * {bcrypt}$2a$10$... — the {id} prefix says which algorithm made the hash, so we can move to
     * Argon2 later without breaking old hashes. BCrypt is deliberately SLOW (~100 ms): fine for one
     * login, terrible for an attacker trying billions of guesses.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** CORS: which browser origins may call us, with cookies (allowCredentials). Gateway takes over in Phase 5. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${pulse.cors.allowed-origins:}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "If-Match"));
        c.setAllowCredentials(true);                 // send/receive cookies cross-origin (3000 -> 8081)
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, String detail) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
