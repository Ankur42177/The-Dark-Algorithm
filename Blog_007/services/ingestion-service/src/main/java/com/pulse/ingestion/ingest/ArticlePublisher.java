package com.pulse.ingestion.ingest;

import com.pulse.events.EventTypes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends stored-but-unpublished articles to Kafka — the same OUTBOX idea as content-service (Phase 6):
 * raw_items.published_at IS NULL = "still to announce". If Kafka is down, the job fails (and is retried by
 * RabbitMQ); the rows stay unpublished and go out on the next successful run. Key = sourceId.
 */
@Component
public class ArticlePublisher {

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;

    public ArticlePublisher(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    record Pending(UUID id, String payload) {
    }

    @Transactional
    public int publishPending(UUID sourceId) {
        List<Pending> rows = jdbc.sql("""
                        SELECT id, payload::text FROM raw_items
                         WHERE source_id = :s AND published_at IS NULL
                         ORDER BY fetched_at LIMIT 500
                           FOR UPDATE SKIP LOCKED""")
                .param("s", sourceId).query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2))).list();
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            CompletableFuture.allOf(rows.stream()
                    .map(r -> kafka.send(EventTypes.TOPIC_ARTICLE_EVENTS, sourceId.toString(), r.payload()))
                    .toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka unavailable: " + e.getClass().getSimpleName(), e);   // transient → retry
        }
        jdbc.sql("UPDATE raw_items SET published_at = now() WHERE id IN (:ids)")
                .param("ids", rows.stream().map(Pending::id).toList()).update();
        return rows.size();
    }
}
