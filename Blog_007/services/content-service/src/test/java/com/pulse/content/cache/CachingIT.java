package com.pulse.content.cache;

import com.pulse.content.IntegrationTestBase;
import com.pulse.content.post.PostDtos.TrendingItem;
import com.pulse.content.post.PostService;
import com.pulse.content.post.Section;
import com.pulse.content.views.ViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3: cache-aside, single-flight, generation eviction, view counting, trending, decay lock. */
class CachingIT extends IntegrationTestBase {

    @Autowired PostService postService;
    @Autowired PostCache cache;
    @Autowired ViewService viewService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void firstReadFillsTheCacheAndSecondReadIsServedFromRedis() {
        assertThat(redis.hasKey(CacheKeys.post("rag-in-one-picture"))).isFalse();

        var first = postService.getPublishedBySlug("rag-in-one-picture");       // miss → Postgres → SET
        assertThat(redis.hasKey(CacheKeys.post("rag-in-one-picture"))).isTrue();
        Long ttl = redis.getExpire(CacheKeys.post("rag-in-one-picture"));
        assertThat(ttl).isBetween(3240L, 3960L);                               // 1 h ± 10 % jitter

        // Prove the 2nd read doesn't touch Postgres: change the row behind the cache's back.
        jdbc.sql("UPDATE posts SET title = 'changed in DB' WHERE slug = 'rag-in-one-picture'").update();
        var second = postService.getPublishedBySlug("rag-in-one-picture");
        assertThat(second.title()).isEqualTo(first.title());                    // stale-but-fast from Redis

        postService.evict("rag-in-one-picture");                               // what Phase 4/6 will call
        assertThat(postService.getPublishedBySlug("rag-in-one-picture").title()).isEqualTo("changed in DB");
        jdbc.sql("UPDATE posts SET title = 'RAG in one picture' WHERE slug = 'rag-in-one-picture'").update();
        postService.evict("rag-in-one-picture");
    }

    @Test
    void bumpingTheGenerationMakesOldListKeysUnreachable() {
        long before = cache.generation(CacheKeys.LIST_GENERATION);
        postService.listPublished(Section.TECH, null, null, 10);
        assertThat(redis.hasKey(CacheKeys.list(before, Section.TECH, null, null, 10))).isTrue();

        postService.evict("anything");
        long after = cache.generation(CacheKeys.LIST_GENERATION);

        assertThat(after).isEqualTo(before + 1);
        postService.listPublished(Section.TECH, null, null, 10);
        assertThat(redis.hasKey(CacheKeys.list(after, Section.TECH, null, null, 10))).isTrue();
    }

    @Test
    void singleFlightLoadsOnceForManyConcurrentMisses() throws Exception {
        AtomicInteger dbLoads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {        // 50 concurrent "readers"
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.getOrLoad("test", "cache:test:sf", Duration.ofMinutes(1), new TypeReference<String>() { }, () -> {
                        dbLoads.incrementAndGet();
                        sleep(200);                                             // a slow DB query
                        return "value";
                    });
                }));
            }
            start.countDown();
            for (Future<String> f : results) {
                assertThat(f.get()).isEqualTo("value");
            }
        }
        assertThat(dbLoads.get()).isEqualTo(1);                                 // not 50!
    }

    @Test
    void viewsAreCountedInRedisAndFlushedToPostgres() {
        for (int i = 0; i < 5; i++) {
            viewService.record("rag-in-one-picture");
        }
        viewService.record("arsenal-pressing-numbers");
        assertThat(redis.opsForValue().get(CacheKeys.views("rag-in-one-picture"))).isEqualTo("5");

        int flushed = viewService.flushViews();

        assertThat(flushed).isEqualTo(2);
        assertThat(redis.hasKey(CacheKeys.views("rag-in-one-picture"))).isFalse(); // GETDEL emptied it
        Long views = jdbc.sql("""
                SELECT v.views FROM post_daily_views v JOIN posts p ON p.id = v.post_id
                 WHERE p.slug = 'rag-in-one-picture'""").query(Long.class).single();
        assertThat(views).isGreaterThanOrEqualTo(5);
    }

    @Test
    void trendingIsOrderedByViewsAndFilteredBySection() {
        for (int i = 0; i < 3; i++) viewService.record("what-is-an-ai-agent");
        for (int i = 0; i < 7; i++) viewService.record("rag-in-one-picture");
        viewService.record("arsenal-pressing-numbers");

        List<TrendingItem> ai = viewService.trending(Section.AI, 5);
        assertThat(ai).extracting(TrendingItem::slug).containsExactly("rag-in-one-picture", "what-is-an-ai-agent");
        assertThat(ai.getFirst().views()).isEqualTo(7);
        assertThat(viewService.trending(null, 5)).hasSize(3);
    }

    @Test
    void decayRunsOncePerHourAcrossReplicasThanksToTheLock() {
        for (int i = 0; i < 10; i++) viewService.record("rag-in-one-picture");

        assertThat(viewService.decayTrending()).isTrue();                       // replica A wins the lock
        assertThat(viewService.decayTrending()).isFalse();                      // replica B: already done
        assertThat(redis.opsForZSet().score(CacheKeys.trending(null), "rag-in-one-picture")).isEqualTo(9.0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
