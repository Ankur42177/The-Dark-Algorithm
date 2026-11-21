package com.pulse.assistant.search;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * pgvector access with plain SQL (JdbcClient) — every query is visible and EXPLAIN-able.
 * A vector is sent as text like '[0.12,-0.03,…]' and cast with ::vector.
 */
@Repository
public class VectorStore {

    public record Chunk(String docType, String docId, int chunkIndex, String title, String url, String section,
                        String source, String content, float[] embedding, Instant publishedAt) {
    }

    public record Match(String docType, String docId, String title, String url, String section, String source,
                        String content, Instant publishedAt, double similarity) {
    }

    private final JdbcClient jdbc;

    public VectorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Replace all chunks of one document atomically (re-embedding an edited post must not leave stale chunks). */
    @Transactional
    public void replaceDocument(String docType, String docId, List<Chunk> chunks) {
        for (Chunk c : chunks) {
            jdbc.sql("""
                            INSERT INTO doc_chunks (doc_type, doc_id, chunk_index, title, url, section, source, content, embedding, published_at, updated_at)
                            VALUES (:t, :d, :i, :title, :url, :section, :source, :content, CAST(:emb AS vector), :pub, now())
                            ON CONFLICT (doc_type, doc_id, chunk_index) DO UPDATE
                               SET title = EXCLUDED.title, url = EXCLUDED.url, section = EXCLUDED.section, source = EXCLUDED.source,
                                   content = EXCLUDED.content, embedding = EXCLUDED.embedding,
                                   published_at = EXCLUDED.published_at, updated_at = now()""")
                    .param("t", c.docType()).param("d", c.docId()).param("i", c.chunkIndex())
                    .param("title", c.title()).param("url", c.url()).param("section", c.section()).param("source", c.source())
                    .param("content", c.content()).param("emb", toLiteral(c.embedding()))
                    .param("pub", c.publishedAt() == null ? null : Timestamp.from(c.publishedAt()))
                    .update();
        }
        // The post got shorter? Remove chunks beyond the new last index.
        jdbc.sql("DELETE FROM doc_chunks WHERE doc_type = :t AND doc_id = :d AND chunk_index >= :n")
                .param("t", docType).param("d", docId).param("n", chunks.size()).update();
    }

    public int deleteDocument(String docType, String docId) {
        return jdbc.sql("DELETE FROM doc_chunks WHERE doc_type = :t AND doc_id = :d")
                .param("t", docType).param("d", docId).update();
    }

    /**
     * Nearest chunks by COSINE distance (<=>), using the HNSW index. Similarity = 1 - distance.
     * We fetch extra rows and keep the best chunk per document, so one long post can't fill every slot.
     */
    public List<Match> nearest(float[] query, String section, int limit) {
        String where = section == null ? "" : "WHERE section = :section";
        var spec = jdbc.sql("""
                        SELECT doc_type, doc_id, title, url, section, source, content, published_at,
                               1 - (embedding <=> CAST(:q AS vector)) AS similarity
                          FROM doc_chunks %s
                         ORDER BY embedding <=> CAST(:q AS vector)
                         LIMIT :k""".formatted(where))
                .param("q", toLiteral(query)).param("k", limit * 4);
        if (section != null) {
            spec = spec.param("section", section);
        }
        List<Match> rows = spec.query((rs, n) -> new Match(rs.getString("doc_type"), rs.getString("doc_id"), rs.getString("title"),
                rs.getString("url"), rs.getString("section"), rs.getString("source"), rs.getString("content"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                rs.getDouble("similarity"))).list();
        java.util.LinkedHashMap<String, Match> best = new java.util.LinkedHashMap<>();
        for (Match m : rows) {
            best.putIfAbsent(m.docType() + ":" + m.docId(), m);   // rows are sorted, so first = best chunk
        }
        return best.values().stream().limit(limit).toList();
    }

    public long countChunks() {
        return jdbc.sql("SELECT count(*) FROM doc_chunks").query(Long.class).single();
    }

    /** Idempotent consumer: true the FIRST time an event id is seen, false for duplicates. */
    public boolean markProcessed(String eventId) {
        return jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventId).update() == 1;
    }

    static String toLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
