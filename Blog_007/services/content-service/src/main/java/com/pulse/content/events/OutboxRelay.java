package com.pulse.content.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * POLLING PUBLISHER: copies unpublished outbox rows to Kafka.
 *
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED}: with 3 replicas polling at once, each locks DIFFERENT rows
 *       (locked rows are skipped, not waited for) → parallel and never double-sent by two relays.</li>
 *   <li>Send the whole batch, wait for Kafka's acks (acks=all), THEN mark rows published — all inside
 *       one DB transaction. If Kafka fails, the transaction rolls back and the rows are retried.</li>
 *   <li>Crash after Kafka acked but before COMMIT → the batch is sent again: AT-LEAST-ONCE delivery.
 *       That's why every consumer is idempotent (processed_events).</li>
 * </ul>
 * Alternative (stretch goal): Debezium CDC reads the Postgres WAL instead of polling.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record Row(UUID id, UUID aggregateId, String topic, String payload) {
    }

    private volatile long lastWarn = 0;
    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metrics;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.metrics = metrics;
    }

    /** Every 250 ms: small delay between "publish" and "event on Kafka", cheap when there's nothing to do. */
    @Scheduled(fixedDelayString = "${pulse.outbox.poll-interval:250ms}")
    @Transactional
    public int relay() {
        List<Row> rows = jdbc.sql("""
                        SELECT id, aggregate_id, topic, payload::text AS payload
                          FROM outbox
                         WHERE published_at IS NULL
                         ORDER BY created_at
                         LIMIT 100
                           FOR UPDATE SKIP LOCKED""")
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("topic"), rs.getString("payload")))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
        for (Row r : rows) {
            // key = aggregate (post) id → same partition → per-post ordering preserved
            sends.add(kafka.send(r.topic(), r.aggregateId().toString(), r.payload()));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Kafka down/slow: release the row locks (rollback) and try again on the next tick.
            // Nothing is lost — the rows simply stay unpublished. Readers are unaffected.
            metrics.counter("pulse.outbox.relay.failures").increment();
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            if (System.currentTimeMillis() - lastWarn > 30_000) {
                lastWarn = System.currentTimeMillis();
                log.warn("Kafka unavailable, {} outbox events waiting ({}). Will retry.", rows.size(), e.getClass().getSimpleName());
            }
            return 0;
        }
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", rows.stream().map(Row::id).toList())
                .update();
        metrics.counter("pulse.outbox.relayed").increment(rows.size());
        log.debug("Relayed {} outbox events to Kafka", rows.size());
        return rows.size();
    }

    /** Housekeeping: published rows older than 7 days are no longer needed. */
    @Scheduled(cron = "${pulse.outbox.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public int cleanup() {
        return jdbc.sql("DELETE FROM outbox WHERE published_at < now() - interval '7 days'").update();
    }
}
