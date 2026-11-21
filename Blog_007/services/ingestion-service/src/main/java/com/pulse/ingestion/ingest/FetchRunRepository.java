package com.pulse.ingestion.ingest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class FetchRunRepository {

    public record FetchRun(UUID id, UUID sourceId, String sourceName, int attempt, Instant startedAt, Instant finishedAt,
                           String status, Integer httpStatus, int itemsSeen, int itemsNew, String error) {
    }

    private final JdbcClient jdbc;

    public FetchRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID start(UUID sourceId, UUID messageId, int attempt) {
        return jdbc.sql("INSERT INTO fetch_runs (source_id, message_id, attempt, status) VALUES (:s, :m, :a, 'RUNNING') RETURNING id")
                .param("s", sourceId).param("m", messageId).param("a", attempt).query(UUID.class).single();
    }

    public void finish(UUID id, String status, Integer http, int seen, int fresh, String error) {
        jdbc.sql("""
                        UPDATE fetch_runs SET finished_at = now(), status = :st, http_status = :h,
                               items_seen = :seen, items_new = :fresh, error = left(:e, 500)
                         WHERE id = :id""")
                .param("st", status).param("h", http).param("seen", seen).param("fresh", fresh).param("e", error).param("id", id)
                .update();
    }

    public List<FetchRun> recent(int limit) {
        return jdbc.sql("""
                        SELECT r.id, r.source_id, s.name, r.attempt, r.started_at, r.finished_at, r.status, r.http_status,
                               r.items_seen, r.items_new, r.error
                          FROM fetch_runs r JOIN feed_sources s ON s.id = r.source_id
                         ORDER BY r.started_at DESC LIMIT :l""")
                .param("l", limit)
                .query((rs, n) -> new FetchRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getString(7), (Integer) rs.getObject(8), rs.getInt(9), rs.getInt(10), rs.getString(11)))
                .list();
    }
}
