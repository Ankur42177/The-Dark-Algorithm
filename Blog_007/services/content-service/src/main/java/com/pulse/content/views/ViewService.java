package com.pulse.content.views;

import com.pulse.content.cache.CacheKeys;
import com.pulse.content.cache.PostCache;
import com.pulse.content.post.PostDtos.TrendingItem;
import com.pulse.content.post.PostQueryRepository;
import com.pulse.content.post.PostService;
import com.pulse.content.post.Section;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * View counting and trending — Redis as a fast counter + leaderboard (PLAN §10).
 *
 * <pre>
 * reader opens a post ──► POST /posts/{slug}/view ──► Redis (one pipelined round trip):
 *                                                      INCR    views:{slug}
 *                                                      SADD    views:dirty {slug}
 *                                                      ZINCRBY trending:{SECTION} 1 {slug}
 *                                                      ZINCRBY trending:ALL       1 {slug}
 * every 60 s (write-behind) ──► SPOP views:dirty → GETDEL views:{slug} → UPSERT post_daily_views
 * every hour (decay)        ──► ZUNIONSTORE trending:X 1 trending:X WEIGHTS 0.9   (old views fade)
 * </pre>
 * View counts are "best effort": if Redis is down we drop views rather than slow down readers.
 */
@Service
public class ViewService {

    private static final Logger log = LoggerFactory.getLogger(ViewService.class);
    private static final double DECAY = 0.9;

    private final StringRedisTemplate redis;
    private final PostCache cache;
    private final PostService postService;
    private final PostQueryRepository queries;

    public ViewService(StringRedisTemplate redis, PostCache cache, PostService postService, PostQueryRepository queries) {
        this.redis = redis;
        this.cache = cache;
        this.postService = postService;
        this.queries = queries;
    }

    /** Count one view. Throws PostNotFoundException (→ 404) for unknown slugs, so bots can't create junk keys. */
    public void record(String slug) {
        Section section = postService.getPublishedBySlug(slug).section(); // cached lookup
        if (!cache.redisUsable()) {
            return;
        }
        try {
            // PIPELINING: send 4 commands in one network round trip instead of 4.
            redis.executePipelined((RedisCallback<Object>) conn -> {
                byte[] member = slug.getBytes(StandardCharsets.UTF_8);
                conn.stringCommands().incr(bytes(CacheKeys.views(slug)));
                conn.setCommands().sAdd(bytes(CacheKeys.VIEWS_DIRTY), member);
                conn.zSetCommands().zIncrBy(bytes(CacheKeys.trending(section)), 1, member);
                conn.zSetCommands().zIncrBy(bytes(CacheKeys.trending(null)), 1, member);
                return null;
            });
        } catch (RuntimeException e) {
            cache.tripBreaker(e);
        }
    }

    /** Top posts by (decayed) views. Empty list if Redis is unavailable — trending is optional UI. */
    public List<TrendingItem> trending(Section section, int limit) {
        if (!cache.redisUsable()) {
            return List.of();
        }
        Set<TypedTuple<String>> top;
        try {
            top = redis.opsForZSet().reverseRangeWithScores(CacheKeys.trending(section), 0, limit - 1);
        } catch (RuntimeException e) {
            cache.tripBreaker(e);
            return List.of();
        }
        if (top == null || top.isEmpty()) {
            return List.of();
        }
        List<String> slugs = top.stream().map(TypedTuple::getValue).toList();
        Map<String, TrendingItem> info = queries.findTrendingInfo(slugs).stream()
                .collect(Collectors.toMap(TrendingItem::slug, Function.identity()));
        List<TrendingItem> result = new ArrayList<>();
        for (TypedTuple<String> t : top) {                       // keep Redis's order (highest first)
            TrendingItem i = info.get(t.getValue());
            if (i != null) {                                      // skip unpublished/deleted posts
                result.add(new TrendingItem(i.slug(), i.title(), i.section(), Math.round(t.getScore())));
            }
        }
        return result;
    }

    /**
     * WRITE-BEHIND flush. Safe with many replicas running it at the same time: SPOP and GETDEL are
     * atomic, so each counted view is moved to Postgres exactly once. If the process crashes between
     * GETDEL and the UPSERT, those views are lost — the accepted trade-off of write-behind.
     */
    @Scheduled(fixedDelayString = "${pulse.views.flush-interval:60s}", initialDelayString = "${pulse.views.flush-interval:60s}")
    public int flushViews() {
        if (!cache.redisUsable()) {
            return 0;
        }
        int flushed = 0;
        try {
            List<String> batch;
            while (!(batch = redis.opsForSet().pop(CacheKeys.VIEWS_DIRTY, 100)).isEmpty()) {
                for (String slug : batch) {
                    String n = redis.opsForValue().getAndDelete(CacheKeys.views(slug));
                    if (n != null && Long.parseLong(n) > 0) {
                        flushed += queries.addDailyViews(slug, Long.parseLong(n));
                    }
                }
            }
        } catch (RuntimeException e) {
            cache.tripBreaker(e);
        }
        if (flushed > 0) {
            log.debug("Flushed views for {} posts to Postgres", flushed);
        }
        return flushed;
    }

    /**
     * Hourly time decay: multiply every score by 0.9 so recent views matter more than old ones.
     * ZUNIONSTORE with a single source key and a WEIGHT is Redis's way to "multiply all scores".
     * A tiny DISTRIBUTED LOCK (SET NX EX) makes sure only one replica decays per hour — otherwise
     * 3 replicas would decay 3 times. (Phase 7 replaces this hand-made lock with ShedLock.)
     */
    @Scheduled(cron = "${pulse.trending.decay-cron:0 0 * * * *}")
    public boolean decayTrending() {
        if (!cache.redisUsable()) {
            return false;
        }
        try {
            Boolean gotLock = redis.opsForValue().setIfAbsent(CacheKeys.TRENDING_DECAY_LOCK, "1", Duration.ofMinutes(55));
            if (!Boolean.TRUE.equals(gotLock)) {
                return false;                                   // another replica did it this hour
            }
            List<String> keys = new ArrayList<>(List.of(CacheKeys.trending(null)));
            for (Section s : Section.values()) {
                keys.add(CacheKeys.trending(s));
            }
            for (String key : keys) {
                redis.opsForZSet().unionAndStore(key, List.of(), key, Aggregate.SUM, Weights.of(DECAY));
                redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, 0.5); // forget near-zero entries
            }
            return true;
        } catch (RuntimeException e) {
            cache.tripBreaker(e);
            return false;
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
