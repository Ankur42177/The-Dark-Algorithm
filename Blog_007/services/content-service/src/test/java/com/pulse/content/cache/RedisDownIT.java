package com.pulse.content.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 3 "break it" as a test: Redis is unreachable (port 1). The site must keep working from
 * Postgres, quickly — the cache is an optimisation, not a dependency.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.port=1", "pulse.views.flush-interval=1h", "pulse.trending.decay-cron=-"})
@ActiveProfiles("dev")
class RedisDownIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18").withDatabaseName("content_db");

    @Value("${local.server.port}")
    int port;

    @Test
    void siteStillWorksAndStaysFastWithoutRedis() {
        RestTestClient http = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            http.get().uri("/api/v1/posts?limit=3").exchange().expectStatus().isOk();
            http.get().uri("/api/v1/posts/rag-in-one-picture").exchange().expectStatus().isOk();
        }
        http.post().uri("/api/v1/posts/rag-in-one-picture/view").exchange().expectStatus().isAccepted();
        http.get().uri("/api/v1/trending").exchange().expectStatus().isOk().expectBody().json("[]");
        long avgMs = (System.nanoTime() - start) / 1_000_000 / 40;

        // The circuit breaker skips Redis after the first failure, so requests don't each wait 250 ms.
        org.assertj.core.api.Assertions.assertThat(avgMs).isLessThan(100);
    }
}
