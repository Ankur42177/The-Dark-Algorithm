package com.pulse.ingestion.queue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dead-lettered jobs, queryable for the admin DLQ page (a queue can't be browsed without consuming it). */
@Repository
public class DeadLetterRepository {

    public record DeadLetter(UUID id, UUID messageId, UUID sourceId, String sourceName, int attempts, String reason,
                             Instant createdAt, Instant requeuedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DeadLetterRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(FetchFeedMessage m, String reason) {
        jdbc.sql("""
                        INSERT INTO dead_letters (message_id, source_id, attempts, reason, payload)
                        VALUES (:m, :s, :a, left(:r, 1000), CAST(:p AS jsonb))""")
                .param("m", m.messageId()).param("s", m.sourceId()).param("a", m.attempt())
                .param("r", reason).param("p", json.writeValueAsString(m)).update();
    }

    public List<DeadLetter> recent() {
        return jdbc.sql("""
                        SELECT d.id, d.message_id, d.source_id, s.name, d.attempts, d.reason, d.created_at, d.requeued_at
                          FROM dead_letters d LEFT JOIN feed_sources s ON s.id = d.source_id
                         ORDER BY d.created_at DESC LIMIT 200""")
                .query((rs, n) -> new DeadLetter(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()))
                .list();
    }

    /** The feed a dead letter belongs to (empty if unknown, or the source was deleted). */
    public Optional<UUID> sourceOf(UUID id) {
        return jdbc.sql("SELECT source_id FROM dead_letters WHERE id = :id AND source_id IS NOT NULL")
                .param("id", id).query(UUID.class).optional();
    }

    /** Mark as requeued and return its source id (empty if unknown or already requeued). */
    public Optional<UUID> markRequeued(UUID id) {
        return jdbc.sql("UPDATE dead_letters SET requeued_at = now() WHERE id = :id AND requeued_at IS NULL RETURNING source_id")
                .param("id", id).query(UUID.class).optional();
    }
}
