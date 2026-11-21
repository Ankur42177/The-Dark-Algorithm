package com.pulse.content.auth;

import com.pulse.content.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.pulse.content.auth.ForwardedIpSupport.PASSWORD;
import static com.pulse.content.auth.ForwardedIpSupport.login;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test client connects from 127.0.0.1, which IS a trusted proxy here (like the gateway).
 * → X-Forwarded-For is honoured: the throttle counts per REAL client, so one user's typos
 *   don't lock out everybody else behind the gateway.
 */
class ForwardedIpTrustedIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void admin(DynamicPropertyRegistry r) {
        r.add("pulse.auth.admin-password-hash", () -> PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    }

    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;

    @Test
    void throttleUsesTheForwardedClientIpFromATrustedProxy() throws Exception {
        redis.delete(redis.keys("auth:fail:*"));
        for (int i = 0; i < 5; i++) {
            assertThat(login(port, "wrong!", "203.0.113.7")).isEqualTo(401);
        }
        assertThat(login(port, "wrong!", "203.0.113.7")).isEqualTo(429);      // the typo-prone user is throttled
        assertThat(login(port, PASSWORD, "203.0.113.8")).isEqualTo(200);      // another user behind the same proxy is not
        assertThat(redis.hasKey("auth:fail:203.0.113.7")).isTrue();
        assertThat(redis.hasKey("auth:fail:127.0.0.1")).isFalse();
    }
}
