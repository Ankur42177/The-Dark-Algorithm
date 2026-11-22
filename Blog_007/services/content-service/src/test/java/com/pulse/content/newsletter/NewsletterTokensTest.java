package com.pulse.content.newsletter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test (no Spring context, no containers): the HMAC unsubscribe key must be shared by every replica.
 * Found in the Phase 7 review: with an empty PULSE_NEWSLETTER_SECRET each process invented its own key, so on
 * 2+ replicas the unsubscribe link in an email often didn't match the stored hash → 404.
 */
class NewsletterTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";   // 32 bytes

    @Test
    void twoReplicasWithTheSameSecretProduceTheSameUnsubscribeToken() {
        UUID id = UUID.randomUUID();
        NewsletterTokens replicaA = new NewsletterTokens(SECRET, prod());
        NewsletterTokens replicaB = new NewsletterTokens(SECRET, prod());

        assertThat(replicaA.unsubscribeToken(id)).isEqualTo(replicaB.unsubscribeToken(id));
        assertThat(replicaA.unsubscribeToken(id)).isNotEqualTo(replicaA.unsubscribeToken(UUID.randomUUID()));
    }

    @Test
    void aMissingSecretFailsStartupOutsideDevAndTest() {
        assertThatThrownBy(() -> new NewsletterTokens("", prod()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PULSE_NEWSLETTER_SECRET");
        assertThatThrownBy(() -> new NewsletterTokens("too-short", prod()))
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void devMayUseATemporaryKey() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(new NewsletterTokens("", dev).unsubscribeToken(UUID.randomUUID())).hasSize(43);   // 32 bytes base64url
    }

    @Test
    void confirmTokensAreRandomAndOnlyTheirHashIsStored() {
        NewsletterTokens t = new NewsletterTokens(SECRET, prod());
        String a = t.newConfirmToken();
        assertThat(a).isNotEqualTo(t.newConfirmToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(NewsletterTokens.sha256Hex(a)).hasSize(64).isNotEqualTo(a);
    }

    private static MockEnvironment prod() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }
}
