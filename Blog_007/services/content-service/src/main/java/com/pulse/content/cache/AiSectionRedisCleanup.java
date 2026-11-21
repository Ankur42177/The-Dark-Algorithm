package com.pulse.content.cache;

import com.pulse.content.feed.FeedService;
import com.pulse.content.post.Section;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Redis half of migration V6 (AI section merged into TECH). Flyway fixes Postgres, but Redis still holds
 * data written BEFORE the migration:
 * <ul>
 *   <li>{@code trending:AI} — the view leaderboard of the old AI section. Merged into {@code trending:TECH}
 *       with ZUNIONSTORE (scores are added), then deleted, so the TECH page's "Trending" includes them.</li>
 *   <li>{@code cache:post:*}, list and feed caches — cached JSON still says {@code "section":"AI"}. We bump the
 *       list/feed "generation" counters (every old list key becomes unreachable) and delete cached posts.</li>
 * </ul>
 * {@link ApplicationRunner} = "run once after the application has started".
 *
 * <p>Safe to run on every start and on many replicas at once:
 * <ul>
 *   <li>the trending merge only happens while {@code trending:AI} exists (after the first run it doesn't);</li>
 *   <li>the cache flush is guarded by {@code SET migration:v6-ai-to-tech done NX} — only the FIRST replica that
 *       ever starts after V6 flushes, later restarts skip it.</li>
 * </ul>
 * Best effort: if Redis is down we just log. Caches expire on their own (lists 60 s, posts 1 h).
 */
@Component
public class AiSectionRedisCleanup implements ApplicationRunner {

    static final String MARKER = "migration:v6-ai-to-tech";
    private static final Logger log = LoggerFactory.getLogger(AiSectionRedisCleanup.class);

    private final StringRedisTemplate redis;
    private final PostCache cache;

    public AiSectionRedisCleanup(StringRedisTemplate redis, PostCache cache) {
        this.redis = redis;
        this.cache = cache;
    }

    @Override
    @SuppressWarnings("deprecation")   // Section.AI is deprecated: this class exists to get rid of it
    public void run(ApplicationArguments args) {
        if (!cache.redisUsable()) {
            return;
        }
        try {
            String ai = CacheKeys.trending(Section.AI);
            String tech = CacheKeys.trending(Section.TECH);
            if (Boolean.TRUE.equals(redis.hasKey(ai))) {
                // ZUNIONSTORE trending:TECH 2 trending:TECH trending:AI  (default AGGREGATE SUM)
                redis.opsForZSet().unionAndStore(tech, ai, tech);
                redis.delete(ai);
                log.info("V6: merged {} into {}", ai, tech);
            }
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(MARKER, "done"))) {
                cache.bumpGeneration(CacheKeys.LIST_GENERATION);
                cache.bumpGeneration(FeedService.FEED_GENERATION);
                int deleted = deleteCachedPosts();
                log.info("V6: list/feed caches invalidated, {} cached posts deleted", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("V6 Redis cleanup skipped ({}): caches will expire on their own", e.getClass().getSimpleName());
        }
    }

    /** SCAN (not KEYS): walks the keyspace in small steps instead of blocking Redis for the whole scan. */
    private int deleteCachedPosts() {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> c = redis.scan(ScanOptions.scanOptions().match("cache:post:*").count(500).build())) {
            c.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        return keys.size();
    }
}
