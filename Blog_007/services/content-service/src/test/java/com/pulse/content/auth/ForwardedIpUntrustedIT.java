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
 * Only 10.9.9.9 (a pretend gateway) is trusted; the test client at 127.0.0.1 is NOT.
 * → a spoofed X-Forwarded-For is ignored: an attacker can't rotate fake IPs to dodge the throttle.
 */
class ForwardedIpUntrustedIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("pulse.auth.admin-password-hash", () -> PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        r.add("server.tomcat.remoteip.internal-proxies", () -> "10\\.9\\.9\\.9");
    }

    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;

    @Test
    void spoofedForwardedForFromAnUntrustedPeerIsIgnored() throws Exception {
        redis.delete(redis.keys("auth:fail:*"));
        for (int i = 0; i < 5; i++) {
            assertThat(login(port, "wrong!", "198.51.100." + i)).isEqualTo(401);   // a "different" IP every time
        }
        // All five were counted against the REAL peer address, so a sixth fake IP doesn't help.
        assertThat(login(port, PASSWORD, "198.51.100.99")).isEqualTo(429);
        assertThat(redis.keys("auth:fail:198.51.100.*")).isEmpty();
        assertThat(redis.keys("auth:fail:*")).hasSize(1);
    }
}
