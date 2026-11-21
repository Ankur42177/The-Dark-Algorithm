package com.pulse.ingestion.source;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FeedSourceRepository {

    /** After this many failed attempts in a row, the source is switched off (a per-source circuit breaker). */
    public static final int DISABLE_AFTER_FAILURES = 10;

    private static final String COLUMNS = """
            id, name, site_url, feed_url, section, fetch_interval_min, etag, last_modified, last_fetched_at,
            next_fetch_at, consecutive_failures, active, disabled_reason""";

    private final JdbcClient jdbc;

    public FeedSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeedSource> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM feed_sources ORDER BY section, name").query(FeedSourceRepository::map).list();
    }

    public Optional<FeedSource> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM feed_sources WHERE id = :id").param("id", id).query(FeedSourceRepository::map).optional();
    }

    /**
     * Claim every due source in ONE atomic statement: set its next fetch time and return it. Even two
     * schedulers running this at the same instant can't both get the same source (row locks).
     */
    public List<UUID> claimDue() {
        return jdbc.sql("""
                        UPDATE feed_sources SET next_fetch_at = now() + make_interval(mins => fetch_interval_min)
                         WHERE active AND next_fetch_at <= now()
                        RETURNING id""")
                .query(UUID.class).list();
    }

    public UUID create(String name, String siteUrl, String feedUrl, String section, int interval) {
        return jdbc.sql("""
                        INSERT INTO feed_sources (name, site_url, feed_url, section, fetch_interval_min)
                        VALUES (:n, :s, :f, :sec, :i) RETURNING id""")
                .param("n", name).param("s", siteUrl).param("f", feedUrl).param("sec", section).param("i", interval)
                .query(UUID.class).single();
    }

    public void update(UUID id, Boolean active, Integer interval, String feedUrl) {
        jdbc.sql("""
                        UPDATE feed_sources
                           SET active = coalesce(CAST(:a AS boolean), active),
                               fetch_interval_min = coalesce(CAST(:i AS integer), fetch_interval_min),
                               feed_url = coalesce(CAST(:f AS varchar), feed_url),
                               consecutive_failures = CASE WHEN CAST(:a AS boolean) THEN 0 ELSE consecutive_failures END,
                               disabled_reason = CASE WHEN CAST(:a AS boolean) THEN NULL ELSE disabled_reason END,
                               next_fetch_at = CASE WHEN CAST(:a AS boolean) THEN now() ELSE next_fetch_at END
                         WHERE id = :id""")
                .param("a", active).param("i", interval).param("f", feedUrl).param("id", id)
                .update();
    }

    public void recordSuccess(UUID id, String etag, String lastModified) {
        jdbc.sql("""
                        UPDATE feed_sources SET last_fetched_at = now(), consecutive_failures = 0,
                               etag = coalesce(CAST(:e AS varchar), etag), last_modified = coalesce(CAST(:lm AS varchar), last_modified)
                         WHERE id = :id""")
                .param("e", etag).param("lm", lastModified).param("id", id).update();
    }

    /** +1 failure; switch the source off after {@link #DISABLE_AFTER_FAILURES}. Returns true if it was disabled now. */
    public boolean recordFailure(UUID id, String reason) {
        return jdbc.sql("""
                        UPDATE feed_sources
                           SET consecutive_failures = consecutive_failures + 1,
                               active = CASE WHEN consecutive_failures + 1 >= :max THEN false ELSE active END,
                               disabled_reason = CASE WHEN consecutive_failures + 1 >= :max THEN left(:r, 300) ELSE disabled_reason END
                         WHERE id = :id
                        RETURNING NOT active""")
                .param("max", DISABLE_AFTER_FAILURES).param("r", "Disabled after repeated failures: " + reason).param("id", id)
                .query(Boolean.class).optional().orElse(false);
    }

    private static FeedSource map(ResultSet rs, int n) throws SQLException {
        return new FeedSource(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("site_url"), rs.getString("feed_url"),
                rs.getString("section"), rs.getInt("fetch_interval_min"), rs.getString("etag"), rs.getString("last_modified"),
                ts(rs.getTimestamp("last_fetched_at")), ts(rs.getTimestamp("next_fetch_at")), rs.getInt("consecutive_failures"),
                rs.getBoolean("active"), rs.getString("disabled_reason"));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
