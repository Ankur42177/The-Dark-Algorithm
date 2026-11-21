package com.pulse.content.feed;

import com.pulse.content.IntegrationTestBase;
import com.pulse.events.ArticleIngestedData;
import com.pulse.events.EventEnvelope;
import com.pulse.events.EventTypes;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Phase 7: ingestion.article.events → content-feed-readmodel → feed_items → GET /api/v1/feed. */
class FeedReadModelIT extends IntegrationTestBase {

    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired FeedReadModelConsumer consumer;
    @Value("${local.server.port}") int port;

    @Test
    void articleEventsBuildTheFeedIdempotently() throws Exception {
        String hash = UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000";
        String event = json.writeValueAsString(EventEnvelope.of(EventTypes.ARTICLE_INGESTED, "ingestion-service",
                new ArticleIngestedData(UUID.randomUUID(), "Simon Willison", "AI", "https://simonwillison.net/x/" + hash,
                        hash, "Readmodel test article", "A short excerpt.", "Simon", null, Instant.now())));
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            p.send(new ProducerRecord<>(EventTypes.TOPIC_ARTICLE_EVENTS, "src", event)).get();
            p.send(new ProducerRecord<>(EventTypes.TOPIC_ARTICLE_EVENTS, "src", event)).get();   // redelivery
        }
        await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.sql("SELECT count(*) FROM processed_events WHERE consumer_group = 'content-feed-readmodel'").query(Long.class).single() >= 1);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                jdbc.sql("SELECT count(*) FROM feed_items WHERE url_hash = :h").param("h", hash).query(Long.class).single() == 1);
        consumer.flushRevalidations();                                   // bumps the feed cache generation

        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/feed?section=AI&limit=5").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].title").isEqualTo("Readmodel test article")
                .jsonPath("$.items[0].sourceName").isEqualTo("Simon Willison");
        assertThat(jdbc.sql("SELECT count(*) FROM feed_items WHERE url_hash = :h").param("h", hash).query(Long.class).single()).isEqualTo(1);
        // the event said "AI" (legacy section): stored as TECH since V6 merged the sections
        assertThat(jdbc.sql("SELECT section FROM feed_items WHERE url_hash = :h").param("h", hash).query(String.class).single()).isEqualTo("TECH");
    }
}
