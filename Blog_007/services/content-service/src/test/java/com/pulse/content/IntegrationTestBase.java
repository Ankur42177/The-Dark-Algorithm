package com.pulse.content;

import com.pulse.content.mail.TestMailConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
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
        properties = {"pulse.views.flush-interval=1h", "pulse.trending.decay-cron=-", "pulse.outbox.poll-interval=100ms",
                // never call the developer's running website from tests (Blog_007/.env sets the secret)
                "pulse.web.revalidate-secret=",
                // newsletter: no timer (tests call the job), and every contact message notifies this address
                "pulse.newsletter.cron=-", "pulse.contact.notify=owner@pulse.test"}) // jobs: run manually in tests
@ActiveProfiles("dev")
@Import(TestMailConfig.class)   // emails are captured in memory, never sent or logged
public abstract class IntegrationTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18").withDatabaseName("content_db");

    /** No dedicated Redis module needed: a GenericContainer + @ServiceConnection(name = "redis") is enough. */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    /** Phase 6: a real single-node Kafka (KRaft). Small heap: Docker memory is shared with the whole stack. */
    @ServiceConnection
    protected static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.4.0").withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms128m");

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }
}
