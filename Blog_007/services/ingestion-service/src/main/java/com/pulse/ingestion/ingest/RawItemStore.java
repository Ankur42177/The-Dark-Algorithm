package com.pulse.ingestion.ingest;

import com.pulse.events.ArticleIngestedData;
import com.pulse.events.EventEnvelope;
import com.pulse.events.EventTypes;
import com.pulse.ingestion.source.FeedSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DEDUPLICATION in two layers:
 * <ol>
 *   <li>Redis {@code dedupe:article:{sha256(url)}} — answers "seen it?" in microseconds without touching Postgres.</li>
 *   <li>Postgres {@code raw_items.url_hash UNIQUE} — the durable truth; {@code ON CONFLICT DO NOTHING} makes the
 *       insert itself idempotent even if Redis was flushed or two workers race.</li>
 * </ol>
 * The Redis key is written only AFTER the row exists, so a crash can never mark an article "seen" without storing it.
 */
@Component
public class RawItemStore {

    private static final Duration DEDUPE_TTL = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RawItemStore(JdbcClient jdbc, StringRedisTemplate redis, JsonMapper json) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
    }

    /** @return how many items were new */
    public int saveNew(FeedSource src, List<FeedParser.ParsedItem> items) {
        int fresh = 0;
        for (FeedParser.ParsedItem it : items) {
            String canonical = canonical(it.url());
            String hash = sha256(canonical);
            String key = "dedupe:article:" + hash;
            if (seen(key)) {
                continue;
            }
            ArticleIngestedData data = new ArticleIngestedData(src.id(), src.name(), src.section(), canonical, hash,
                    it.title(), it.excerpt(), it.author(), it.imageUrl(), it.publishedAt());
            // The event (with its unique id) is built ONCE and stored: resends reuse the same id → consumers dedupe.
            String event = json.writeValueAsString(EventEnvelope.of(EventTypes.ARTICLE_INGESTED, "ingestion-service", data));
            int inserted = jdbc.sql("""
                            INSERT INTO raw_items (source_id, guid, url, url_hash, payload)
                            VALUES (:s, left(:g, 1000), :u, :h, CAST(:p AS jsonb))
                            ON CONFLICT (url_hash) DO NOTHING""")
                    .param("s", src.id()).param("g", it.guid()).param("u", canonical).param("h", hash).param("p", event)
                    .update();
            fresh += inserted;
            remember(key);
        }
        return fresh;
    }

    private boolean seen(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (RuntimeException e) {
            return false;                       // Redis down → the UNIQUE constraint still dedupes
        }
    }

    private void remember(String key) {
        try {
            redis.opsForValue().set(key, "1", DEDUPE_TTL);
        } catch (RuntimeException ignored) {
            // optional
        }
    }

    /** Same article, different URLs (tracking params, #fragments) → same hash. */
    static String canonical(String url) {
        URI u = URI.create(url);
        String query = u.getRawQuery() == null ? null : Stream.of(u.getRawQuery().split("&"))
                .filter(p -> !p.toLowerCase().startsWith("utm_") && !p.isBlank())
                .collect(Collectors.joining("&"));
        String scheme = u.getScheme().toLowerCase();
        String host = u.getHost().toLowerCase();
        String port = u.getPort() == -1 ? "" : ":" + u.getPort();
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return scheme + "://" + host + port + path + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
