package com.pulse.assistant.search;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pgvector access with plain SQL (JdbcClient) — every query is visible and EXPLAIN-able.
 * A vector is sent as text like '[0.12,-0.03,…]' and cast with ::vector.
 *
 * <p>Java note: a {@code record} is a compact, immutable data class — the compiler writes the constructor,
 * getters ({@code chunk.title()}), equals/hashCode and toString for us.
 */
@Repository
public class VectorStore {

    /** One row to store. {@code docHash} fingerprints the whole document (see V2 migration). */
    public record Chunk(String docType, String docId, int chunkIndex, String title, String url, String section,
                        String source, String content, float[] embedding, Instant publishedAt, String imageUrl, String docHash) {
    }

    /** One search hit. {@code similarity} = 1 − cosine distance (1.0 = same meaning). */
    public record Match(String docType, String docId, String title, String url, String section, String source,
                        String content, Instant publishedAt, String imageUrl, double similarity) {
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
                            INSERT INTO doc_chunks (doc_type, doc_id, chunk_index, title, url, section, source, content, embedding,
                                                    published_at, image_url, doc_hash, updated_at)
                            VALUES (:t, :d, :i, :title, :url, :section, :source, :content, CAST(:emb AS vector), :pub, :img, :hash, now())
                            ON CONFLICT (doc_type, doc_id, chunk_index) DO UPDATE
                               SET title = EXCLUDED.title, url = EXCLUDED.url, section = EXCLUDED.section, source = EXCLUDED.source,
                                   content = EXCLUDED.content, embedding = EXCLUDED.embedding, published_at = EXCLUDED.published_at,
                                   image_url = EXCLUDED.image_url, doc_hash = EXCLUDED.doc_hash, updated_at = now()""")
                    .param("t", c.docType()).param("d", c.docId()).param("i", c.chunkIndex())
                    .param("title", cut(c.title(), 300)).param("url", cut(c.url(), 600)).param("section", c.section())
                    .param("source", cut(c.source(), 120)).param("content", c.content()).param("emb", toLiteral(c.embedding()))
                    .param("pub", c.publishedAt() == null ? null : Timestamp.from(c.publishedAt()))
                    .param("img", cut(c.imageUrl(), 600)).param("hash", c.docHash())
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

    /** Bulk delete (the reconciler removing documents that no longer exist on the site). */
    public int deleteDocuments(String docType, Collection<String> docIds) {
        if (docIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM doc_chunks WHERE doc_type = :t AND doc_id IN (:ids)")
                .param("t", docType).param("ids", docIds).update();
    }

    /** docId → doc_hash of every stored document of one type (null hash = indexed before V2). */
    public Map<String, String> documentHashes(String docType) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("SELECT doc_id, max(doc_hash) AS h FROM doc_chunks WHERE doc_type = :t GROUP BY doc_id")
                .param("t", docType)
                .query((rs, n) -> out.put(rs.getString("doc_id"), rs.getString("h"))).list();
        return out;
    }

    /**
     * Nearest chunks by COSINE distance (<=>), using the HNSW index. Similarity = 1 - distance.
     * We fetch extra rows and keep the best chunk per document, so one long post can't fill every slot.
     */
    public List<Match> nearest(float[] query, String section, int limit) {
        String where = section == null ? "" : "WHERE section = :section";
        var spec = jdbc.sql("""
                        SELECT doc_type, doc_id, title, url, section, source, content, published_at, image_url,
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
                rs.getString("image_url"), rs.getDouble("similarity"))).list();
        LinkedHashMap<String, Match> best = new LinkedHashMap<>();
        for (Match m : rows) {
            best.putIfAbsent(m.docType() + ":" + m.docId(), m);   // rows are sorted, so first = best chunk
        }
        return best.values().stream().limit(limit).toList();
    }

    public long countChunks() {
        return jdbc.sql("SELECT count(*) FROM doc_chunks").query(Long.class).single();
    }

    /** Index health for /internal/v1/embed/stats: chunks and documents per type, section and source. */
    public List<Map<String, Object>> breakdown() {
        return jdbc.sql("""
                        SELECT doc_type, coalesce(section, '-') AS section, source, count(*) AS chunks, count(DISTINCT doc_id) AS docs
                          FROM doc_chunks GROUP BY 1, 2, 3 ORDER BY 1, 2, 4 DESC""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", rs.getString("doc_type"));
                    m.put("section", rs.getString("section"));
                    m.put("source", rs.getString("source"));
                    m.put("docs", rs.getLong("docs"));
                    m.put("chunks", rs.getLong("chunks"));
                    return m;
                }).list();
    }

    /** Names of the external publishers in the index ("LangChain Blog", "Google DeepMind", …). */
    public List<String> articleSources() {
        return jdbc.sql("SELECT DISTINCT source FROM doc_chunks WHERE doc_type = 'ARTICLE' ORDER BY source")
                .query(String.class).list();
    }

    /** Idempotent consumer: true the FIRST time an event id is seen, false for duplicates. */
    public boolean markProcessed(String eventId) {
        return jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventId).update() == 1;
    }

    /** Undo markProcessed when handling failed, so Kafka's retry is not mistaken for a duplicate. */
    public void unmarkProcessed(String eventId) {
        jdbc.sql("DELETE FROM processed_events WHERE event_id = :id").param("id", eventId).update();
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

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
