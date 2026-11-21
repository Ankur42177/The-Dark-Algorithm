package com.pulse.gateway;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * If Redis is down, fail FAST (reject commands immediately instead of queueing them while reconnecting).
 * RedisRateLimiter then fails OPEN: requests are allowed rather than the whole API going down.
 */
@Configuration
public class RedisConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown() {
        return b -> b.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
    }
}
