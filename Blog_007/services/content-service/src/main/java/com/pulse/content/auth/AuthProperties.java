package com.pulse.content.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed configuration: every "pulse.auth.*" property in application.yml / env vars is bound to this
 * record at startup ({@code @ConfigurationProperties}). Better than many scattered {@code @Value}s:
 * one place, typed (Duration!), and validated.
 *
 * @param adminUsername     login name of the single admin ("ankur")
 * @param adminDisplayName  shown in the UI ("Ankur Kumar")
 * @param adminPasswordHash BCrypt hash from scripts/hash-password.sh (env ADMIN_PASSWORD_HASH)
 * @param jwtPrivateKey     Base64 PKCS#8 RSA private key (env PULSE_JWT_PRIVATE_KEY). Empty = generate (dev only!)
 * @param jwtPublicKey      Base64 X.509 RSA public key (env PULSE_JWT_PUBLIC_KEY)
 * @param issuer            "iss" claim, checked by every verifier
 * @param accessTtl         access-token lifetime (short: 15 min)
 * @param refreshTtl        refresh-token lifetime (7 days)
 * @param cookieSecure      Secure flag on cookies (browsers allow Secure cookies on http://localhost)
 */
@ConfigurationProperties("pulse.auth")
public record AuthProperties(
        String adminUsername,
        String adminDisplayName,
        String adminPasswordHash,
        String jwtPrivateKey,
        String jwtPublicKey,
        String issuer,
        Duration accessTtl,
        Duration refreshTtl,
        boolean cookieSecure) {
}
