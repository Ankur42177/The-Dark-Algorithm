package com.pulse.content;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: a REAL Postgres and a REAL Redis in Docker.
 *
 * <p>"Singleton containers" pattern: the containers are started ONCE (static block) and shared by
 * every test class that extends this, instead of starting new ones per class. Much faster.
 * Spring also caches the application context between classes with the same configuration.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"pulse.views.flush-interval=1h", "pulse.trending.decay-cron=-"}) // jobs: run manually in tests
@ActiveProfiles("dev")
public abstract class IntegrationTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18").withDatabaseName("content_db");

    /** No dedicated Redis module needed: a GenericContainer + @ServiceConnection(name = "redis") is enough. */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}
