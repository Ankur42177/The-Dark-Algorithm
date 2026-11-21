package com.pulse.content.auth;

import com.pulse.content.auth.AdminUserRepository.AdminUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Issues access tokens (JWT, 15 min) and refresh tokens (opaque random string, 7 days, rotated).
 *
 * <pre>
 * login ─► access JWT + refresh R1 (family F)
 * refresh with R1 ─► R1 marked used ─► new access JWT + R2 (family F)
 * refresh with R1 AGAIN (someone stole it!) ─► REUSE DETECTED ─► revoke all of family F ─► 401
 * </pre>
 */
@Service
public class TokenService {

    public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken, AdminUser user) {
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String msg) {
            super(msg);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();   // cryptographically strong randomness

    private final JwtEncoder encoder;
    private final AuthProperties props;
    private final JdbcClient jdbc;
    private final AdminUserRepository users;

    public TokenService(JwtEncoder encoder, AuthProperties props, JdbcClient jdbc, AdminUserRepository users) {
        this.encoder = encoder;
        this.props = props;
        this.jdbc = jdbc;
        this.users = users;
    }

    /** After a successful password check: new family. */
    @Transactional
    public Tokens login(AdminUser user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Rotate: the presented refresh token is single-use.
     * noRollbackFor: when we detect reuse we REVOKE the family and then throw. Without this, Spring would
     * roll back the transaction on the exception — undoing the revocation! (Bug caught by AuthAndAdminIT.)
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Tokens refresh(String rawRefreshToken) {
        record Row(UUID id, UUID familyId, UUID userId, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        }
        Row row = jdbc.sql("""
                        SELECT id, family_id, user_id, expires_at, used_at, revoked_at
                          FROM refresh_tokens WHERE token_hash = :h FOR UPDATE""")
                // FOR UPDATE: lock the row so two simultaneous refreshes with the same token can't both win.
                .param("h", sha256(rawRefreshToken))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        toInstant(rs.getTimestamp("used_at")), toInstant(rs.getTimestamp("revoked_at"))))
                .optional()
                .orElseThrow(() -> new InvalidRefreshTokenException("unknown refresh token"));

        if (row.revokedAt() != null) {
            throw new InvalidRefreshTokenException("refresh token revoked");
        }
        if (row.usedAt() != null) {
            revokeFamily(row.familyId());          // REUSE DETECTED: assume theft, kill every session of this family
            throw new InvalidRefreshTokenException("refresh token reuse detected - session revoked");
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("refresh token expired");
        }
        jdbc.sql("UPDATE refresh_tokens SET used_at = now() WHERE id = :id").param("id", row.id()).update();
        AdminUser user = users.findById(row.userId()).orElseThrow(() -> new InvalidRefreshTokenException("user gone"));
        return issue(user, row.familyId());
    }

    /** Logout: revoke the family of the presented token (if any). */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null) {
            return;
        }
        jdbc.sql("SELECT family_id FROM refresh_tokens WHERE token_hash = :h")
                .param("h", sha256(rawRefreshToken)).query(UUID.class).optional()
                .ifPresent(this::revokeFamily);
    }

    private void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                .param("f", familyId).update();
    }

    private Tokens issue(AdminUser user, UUID familyId) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())                 // who made it
                .subject(user.username())              // who it's about
                .issuedAt(now)
                .expiresAt(exp)                        // short life: a stolen access token is useful for minutes only
                .id(UUID.randomUUID().toString())      // "jti": unique token id (for logs/blocklists)
                .claim("name", user.displayName())
                .claim("roles", List.of(user.role()))  // -> ROLE_ADMIN in Spring Security
                .build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();

        byte[] bytes = new byte[32];                   // 256 random bits: unguessable
        RANDOM.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO refresh_tokens (family_id, user_id, token_hash, expires_at)
                        VALUES (:f, :u, :h, :e)""")
                .param("f", familyId).param("u", user.id()).param("h", sha256(refresh))
                .param("e", Timestamp.from(now.plus(props.refreshTtl())))
                .update();
        return new Tokens(jwt, exp, refresh, user);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
