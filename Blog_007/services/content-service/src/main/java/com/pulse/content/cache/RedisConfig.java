package com.pulse.content.cache;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Redis client tuning + turning on @Scheduled jobs (view flushing, trending decay).
 */
@Configuration
@EnableScheduling   // lets Spring run methods annotated with @Scheduled on a timer
public class RedisConfig {

    /**
     * By default Lettuce QUEUES commands while it is reconnecting to a dead Redis, so every request
     * would wait for the full command timeout. REJECT_COMMANDS makes them fail immediately instead,
     * and {@link PostCache} then falls back to Postgres. A cache must never make the site slower
     * than having no cache at all.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }
}
