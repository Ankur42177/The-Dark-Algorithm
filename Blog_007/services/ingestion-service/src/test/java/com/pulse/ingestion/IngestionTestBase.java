package com.pulse.ingestion;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared setup for ingestion-service integration tests: REAL Postgres, RabbitMQ, Kafka and Redis (Testcontainers)
 * plus a fake internet (WireMock). "Singleton containers": started once and shared by every subclass, and because
 * every subclass has the same configuration Spring reuses ONE application context for all of them.
 *
 * <p>Auth: ingestion-service only VERIFIES tokens, using the public keys at {@code pulse.auth.jwks-uri}. In tests
 * WireMock plays content-service's {@code /.well-known/jwks.json}, serving the public half of {@link #SIGNING_KEY};
 * tests sign tokens with the private half — exactly the production trust chain, minus content-service.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pulse.ingestion.tick=1h", "pulse.ingestion.initial-delay=1h",       // we trigger the scheduler by hand
        "pulse.ingestion.retry-delays=200ms,200ms,200ms,200ms",              // fast backoff for the test
        "pulse.fetch.allowed-private-hosts=localhost", "pulse.fetch.timeout=2s"})
public abstract class IngestionTestBase {

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18").withDatabaseName("ingestion_db");
    @ServiceConnection
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.8-management");
    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0").withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms128m");
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    // gzip off: Jetty would rewrite our ETag "v1" to "v1--gzip" (correct HTTP, but it makes the ETag round-trip
    // assertions depend on the fake server's compression settings rather than on our code).
    protected static final WireMockServer WEB = new WireMockServer(options().dynamicPort().gzipDisabled(true));

    /** Test stand-in for content-service's private signing key. */
    protected static final RSAKey SIGNING_KEY;

    static {
        POSTGRES.start();
        RABBIT.start();
        KAFKA.start();
        REDIS.start();
        WEB.start();                       // never stopped explicitly: the JVM exit cleans it up, like the containers
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.listener.simple.concurrency", () -> "2");
        r.add("pulse.auth.jwks-uri", () -> "http://localhost:" + WEB.port() + JWKS_PATH);
    }

    /** Call after {@code WEB.resetAll()}: (re)publish the public key so tokens can be verified. */
    protected static void stubJwks() {
        WEB.stubFor(get(urlEqualTo(JWKS_PATH)).willReturn(okJson(new JWKSet(SIGNING_KEY.toPublicJWK()).toString())));
    }
}
