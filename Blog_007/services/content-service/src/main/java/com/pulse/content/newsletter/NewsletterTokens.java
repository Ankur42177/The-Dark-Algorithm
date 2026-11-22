package com.pulse.content.newsletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The secret links in newsletter emails.
 *
 * <h2>Confirm token: random, single purpose, expires</h2>
 * 32 bytes from {@link SecureRandom} (a cryptographically strong generator, NOT {@code java.util.Random}) =
 * 256 bits: guessing one is hopeless. Encoded "base64url" (A-Z a-z 0-9 - _) so it is safe inside a URL.
 * We store only {@code SHA-256(token)}: a database leak reveals no working links.
 *
 * <h2>Unsubscribe token: derived with HMAC, so we can re-create it</h2>
 * Every monthly email must contain a working unsubscribe link — but we never store raw tokens, so we could not
 * "look it up" to put it in the next email. Solution: compute it.
 * <pre>
 *   unsubscribeToken = base64url( HMAC-SHA256(serverSecret, "unsubscribe:" + subscriberId) )
 *   database stores  = SHA-256(unsubscribeToken)          (for the lookup when the link is clicked)
 * </pre>
 * HMAC = a hash mixed with a secret key: without the secret nobody can compute a valid token for an id, and the
 * same id always gives the same token, so EVERY email ever sent keeps a working unsubscribe link.
 * The secret ({@code PULSE_NEWSLETTER_SECRET}) lives in the environment, never in the database or git.
 *
 * <p>Why is a plain (fast) SHA-256 OK for storing tokens when passwords need slow BCrypt? Passwords are
 * guessable (humans pick them); 256 random bits are not — there is nothing to brute-force.
 */
@Component
public class NewsletterTokens {

    private static final Logger log = LoggerFactory.getLogger(NewsletterTokens.class);
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public NewsletterTokens(@Value("${pulse.newsletter.token-secret:}") String configuredSecret, Environment env) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            // FAIL FAST outside dev/test. With a per-process random key, replica A stores the unsubscribe hash at
            // confirm time and replica B (different key) puts a different token into the monthly email → every
            // unsubscribe link 404s. That breaks anti-spam law, so refuse to start instead (Phase 7 review, HIGH).
            requireDevOrTest(env, "pulse.newsletter.token-secret (PULSE_NEWSLETTER_SECRET)");
            // Dev convenience. Consequence: after a restart, unsubscribe links in OLD emails stop working.
            this.secret = new byte[32];
            random.nextBytes(secret);
            log.warn("pulse.newsletter.token-secret not set: using a temporary key (unsubscribe links in emails "
                    + "sent before a restart will stop working). Set PULSE_NEWSLETTER_SECRET in production.");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException("pulse.newsletter.token-secret must be at least 32 bytes (e.g. openssl rand -hex 32)");
            }
        }
    }

    /**
     * Throws unless the "dev" or "test" Spring profile is active. Shared by every "secret with a throw-away
     * default" (newsletter HMAC key, contact IP-hash key): convenient on a laptop, dangerous anywhere else.
     */
    public static void requireDevOrTest(Environment env, String what) {
        if (!env.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException(what + " is not set. A random per-process key is only allowed with the "
                    + "dev/test profile: every replica would use a different key.");
        }
    }

    /** A fresh random token for a confirm link. */
    public String newConfirmToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return B64URL.encodeToString(bytes);
    }

    /** The (always identical) unsubscribe token of one subscriber. */
    public String unsubscribeToken(UUID subscriberId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");   // Mac objects are not thread-safe: one per call (cheap)
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64URL.encodeToString(mac.doFinal(("unsubscribe:" + subscriberId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);   // every JVM has it; never happens
        }
    }

    /** What we store and look up: 64 lower-case hex characters. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
