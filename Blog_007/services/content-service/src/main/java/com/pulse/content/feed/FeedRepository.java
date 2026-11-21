package com.pulse.content.feed;

import com.pulse.content.post.Cursor;
import com.pulse.content.post.Section;
import com.pulse.events.ArticleIngestedData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class FeedRepository {

    private final JdbcClient jdbc;

    public FeedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** UPSERT by url_hash: replaying the same event (or a corrected title) never creates duplicates. */
    public void upsert(ArticleIngestedData a) {
        jdbc.sql("""
                        INSERT INTO feed_items (source_id, source_name, url, url_hash, title, excerpt, author, section, image_url, published_at)
                        VALUES (:sid, :sn, :url, :h, left(:t, 300), left(:ex, 300), left(:au, 120), :sec, :img, :pub)
                        ON CONFLICT (url_hash) DO UPDATE
                           SET title = EXCLUDED.title, excerpt = EXCLUDED.excerpt, author = EXCLUDED.author,
                               image_url = EXCLUDED.image_url""")
                .param("sid", a.sourceId()).param("sn", a.sourceName()).param("url", a.url()).param("h", a.urlHash())
                .param("t", a.title()).param("ex", a.excerpt()).param("au", a.author()).param("sec", Section.canonical(a.section()))   // legacy "AI" events → TECH (V6)
                .param("img", a.imageUrl()).param("pub", Timestamp.from(a.publishedAt()))
                .update();
    }

    /** Keyset pagination, newest first — exactly like posts (Phase 1). */
    public List<FeedItem> find(Section section, String source, Cursor after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, source_name, url, title, excerpt, author, section, image_url, published_at
                  FROM feed_items WHERE true
                """);
        List<String> where = new ArrayList<>();
        if (section != null) {
            where.add("section = :section");
        }
        if (source != null) {
            where.add("source_name = :source");
        }
        if (after != null) {
            where.add("(published_at, id) < (:ts, :id)");
        }
        where.forEach(w -> sql.append("   AND ").append(w).append('\n'));
        sql.append(" ORDER BY published_at DESC, id DESC LIMIT :limit");
        JdbcClient.StatementSpec st = jdbc.sql(sql.toString()).param("limit", limit);
        if (section != null) {
            st = st.param("section", section.name());
        }
        if (source != null) {
            st = st.param("source", source);
        }
        if (after != null) {
            st = st.param("ts", Timestamp.from(after.publishedAt())).param("id", after.id());
        }
        return st.query((rs, n) -> new FeedItem(rs.getObject("id", UUID.class), rs.getString("source_name"), rs.getString("url"),
                rs.getString("title"), rs.getString("excerpt"), rs.getString("author"), Section.valueOf(rs.getString("section")),
                rs.getString("image_url"), rs.getTimestamp("published_at").toInstant())).list();
    }
}
