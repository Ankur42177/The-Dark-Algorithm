package com.pulse.ingestion;

import com.pulse.events.EventTypes;
import com.pulse.ingestion.queue.FeedFetchScheduler;
import com.pulse.ingestion.queue.FetchFeedMessage;
import com.pulse.ingestion.queue.JobPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end with REAL Postgres, RabbitMQ, Kafka and Redis (Testcontainers) and a fake internet (WireMock):
 * scheduler → RabbitMQ → worker → feed → raw_items → Kafka; conditional GET; retries → DLQ; ShedLock.
 */
class IngestionPipelineIT extends IngestionTestBase {

    @Autowired JdbcClient jdbc;
    @Autowired JobPublisher jobs;
    @Autowired FeedFetchScheduler scheduler;
    @Autowired MeterRegistry metrics;

    static final String RSS = """
            <?xml version="1.0"?><rss version="2.0"><channel><title>Fake blog</title><link>https://fake.example</link><description>d</description>
            <item><title>First post</title><link>https://fake.example/1?utm_source=rss</link><description>One</description><pubDate>Sat, 21 Nov 2026 06:00:00 GMT</pubDate></item>
            <item><title>Second post</title><link>https://fake.example/2</link><description>Two</description><pubDate>Sat, 21 Nov 2026 05:00:00 GMT</pubDate></item>
            </channel></rss>""";

    @BeforeEach
    void isolate() {
        jdbc.sql("UPDATE feed_sources SET active = false").update();         // never touch the real seeded feeds
        WEB.resetAll();
        stubJwks();
        WEB.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
    }

    private UUID source(String path) {
        return jdbc.sql("""
                        INSERT INTO feed_sources (name, site_url, feed_url, section, next_fetch_at)
                        VALUES (:n, 'https://fake.example', :u, 'TECH', now() + interval '1 day') RETURNING id""")
                .param("n", "Fake " + path + " " + UUID.randomUUID()).param("u", "http://localhost:" + WEB.port() + path + "?" + UUID.randomUUID())
                .query(UUID.class).single();
    }

    @Test
    void fetchStoreAndPublishThenNotModified() {
        WEB.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/feed.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"v1\"").withHeader("Content-Type", "application/rss+xml").withBody(RSS)));
        UUID id = source("/feed.xml");
        try (KafkaConsumer<String, String> c = consumer()) {
            c.subscribe(List.of(EventTypes.TOPIC_ARTICLE_EVENTS));
            jobs.enqueue(FetchFeedMessage.first(id));
            await().atMost(Duration.ofSeconds(20)).until(() -> count("SELECT count(*) FROM raw_items WHERE source_id = '" + id + "' AND published_at IS NOT NULL") == 2);
            List<ConsumerRecord<String, String>> got = new ArrayList<>();
            long end = System.currentTimeMillis() + 15_000;
            while (got.size() < 2 && System.currentTimeMillis() < end) {
                c.poll(Duration.ofMillis(300)).forEach(r -> { if (r.key().equals(id.toString())) got.add(r); });
            }
            assertThat(got).hasSize(2);
            assertThat(got.getFirst().value()).contains("ingestion.article.ingested").doesNotContain("utm_source");
        }
        assertThat(jdbc.sql("SELECT etag FROM feed_sources WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("\"v1\"");

        // Second run: the server answers 304 to our If-None-Match → nothing new, nothing re-published.
        WEB.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/feed.xml")).withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));
        jobs.enqueue(FetchFeedMessage.first(id));
        await().atMost(Duration.ofSeconds(20)).until(() -> count("SELECT count(*) FROM fetch_runs WHERE source_id = '" + id + "' AND status = 'NOT_MODIFIED'") == 1);
        assertThat(count("SELECT count(*) FROM raw_items WHERE source_id = '" + id + "'")).isEqualTo(2);
    }

    @Test
    void transientFailuresAreRetriedFiveTimesThenDeadLettered() {
        WEB.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/broken")).willReturn(aResponse().withStatus(503)));
        UUID id = source("/broken");
        jobs.enqueue(FetchFeedMessage.first(id));
        await().atMost(Duration.ofSeconds(30)).until(() -> count("SELECT count(*) FROM dead_letters WHERE source_id = '" + id + "'") == 1);
        assertThat(jdbc.sql("SELECT attempts FROM dead_letters WHERE source_id = :id").param("id", id).query(Integer.class).single()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM fetch_runs WHERE source_id = '" + id + "' AND status = 'FAILED'")).isEqualTo(5);
        WEB.verify(5, getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/broken")));
    }

    @Test
    void permanentFailuresSkipRetries() {
        WEB.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/evil"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "http://169.254.169.254/latest/meta-data/")));
        UUID id = source("/evil");
        jobs.enqueue(FetchFeedMessage.first(id));
        await().atMost(Duration.ofSeconds(20)).until(() -> count("SELECT count(*) FROM dead_letters WHERE source_id = '" + id + "'") == 1);
        assertThat(jdbc.sql("SELECT attempts || ' ' || reason FROM dead_letters WHERE source_id = :id").param("id", id).query(String.class).single())
                .startsWith("1 ").containsAnyOf("SSRF", "Blocked");
    }

    @Test
    void shedLockLetsOnlyOneSchedulerRunPerWindow() {
        WEB.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/due.*")).willReturn(aResponse().withStatus(304)));
        for (int i = 0; i < 3; i++) {
            jdbc.sql("UPDATE feed_sources SET next_fetch_at = now(), active = true WHERE id = :id").param("id", source("/due" + i)).update();
        }
        double before = enqueued();
        scheduler.enqueueDueFeeds();                                        // replica A: gets the lock
        assertThat(enqueued() - before).isEqualTo(3);

        for (int i = 3; i < 5; i++) {                                       // two more feeds become due…
            jdbc.sql("UPDATE feed_sources SET next_fetch_at = now(), active = true WHERE id = :id").param("id", source("/due" + i)).update();
        }
        scheduler.enqueueDueFeeds();                                        // …but "replica B" fires within lockAtLeastFor (5 s)
        assertThat(enqueued() - before).isEqualTo(3);                       // skipped by ShedLock
        assertThat(count("SELECT count(*) FROM shedlock WHERE name = 'enqueue-due-feeds'")).isEqualTo(1);
    }

    private double enqueued() {
        var c = metrics.find("pulse.ingestion.enqueued").counter();
        return c == null ? 0 : c.count();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", "it-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest", "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
    }
}
