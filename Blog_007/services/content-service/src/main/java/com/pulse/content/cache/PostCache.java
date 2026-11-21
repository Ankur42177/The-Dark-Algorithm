package com.pulse.content.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * CACHE-ASIDE with Redis (PLAN §10). The application, not Redis, decides what to cache:
 * <pre>
 *   1. GET key from Redis          → hit?  return it                 (~0.3 ms)
 *   2. miss → load from Postgres   → SET key value EX ttl±jitter     (~2 ms)
 * </pre>
 * Protections built in (each one is a lesson in Learning_project → Phase 3):
 * <ul>
 *   <li><b>TTL jitter</b>: ±10% random TTL so keys written together don't all expire together.</li>
 *   <li><b>Single-flight</b>: if 100 requests miss the same key at once, only ONE loads from the DB;
 *       the other 99 wait for its result (per JVM — N replicas ⇒ at most N loads).</li>
 *   <li><b>Fail-open + circuit breaker</b>: if Redis errors, skip it for 5 s and serve from Postgres.
 *       The cache is an optimisation, never a single point of failure.</li>
 * </ul>
 */
@Component
public class PostCache {

    private static final Logger log = LoggerFactory.getLogger(PostCache.class);
    private static final long BREAKER_OPEN_MILLIS = 5_000;

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    /** key → the in-progress load for that key (single-flight). */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    /** Circuit breaker state: don't call Redis until this time (epoch millis). volatile = visible to all threads. */
    private volatile long redisSkipUntil = 0;

    /** Switch for experiments: PULSE_CACHE_ENABLED=false runs every read against Postgres. */
    private final boolean enabled;

    public PostCache(StringRedisTemplate redis, JsonMapper json, MeterRegistry metrics,
                     @Value("${pulse.cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.json = json;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /**
     * Return the cached value for {@code key}, or run {@code loader}, cache its result and return it.
     *
     * @param cacheName short label for metrics ("post", "list")
     * @param baseTtl   TTL before jitter
     * @param type      Jackson type, e.g. {@code new TypeReference<CursorPage<PostSummary>>() {}}
     */
    public <T> T getOrLoad(String cacheName, String key, Duration baseTtl, TypeReference<T> type, Supplier<T> loader) {
        String cached = safeGet(key);
        if (cached != null) {
            count(cacheName, "hit");
            return json.readValue(cached, type);
        }
        count(cacheName, "miss");
        return singleFlight(key, () -> {
            T value = loader.get();
            safeSet(key, json.writeValueAsString(value), jitter(baseTtl));
            return value;
        });
    }

    /** Remove one key (e.g. a post was edited). */
    public void evict(String key) {
        if (redisUsable()) {
            try {
                redis.delete(key);
            } catch (RuntimeException e) {
                tripBreaker(e);
            }
        }
    }

    /** Current value of a counter key, used for "generation" namespacing of list keys. 0 if unknown. */
    public long generation(String key) {
        String v = safeGet(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    /** INCR a generation key: every list key built with the old number is now unreachable (and expires). */
    public void bumpGeneration(String key) {
        if (redisUsable()) {
            try {
                redis.opsForValue().increment(key);
            } catch (RuntimeException e) {
                tripBreaker(e);
            }
        }
    }

    // ------------------------------------------------------------------ internals

    @SuppressWarnings("unchecked")
    private <T> T singleFlight(String key, Supplier<T> load) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) {
            metrics.counter("pulse.cache.singleflight.waits").increment();
            try {
                return (T) leader.join();            // follower: wait for the leader's result
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        }
        try {                                        // leader: do the work once
            T value = load.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private String safeGet(String key) {
        if (!redisUsable()) {
            return null;
        }
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            tripBreaker(e);
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        if (!redisUsable()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            tripBreaker(e);
        }
    }

    /** Package-visible so ViewService can share the breaker. */
    public boolean redisUsable() {
        return enabled && System.currentTimeMillis() >= redisSkipUntil;
    }

    public void tripBreaker(RuntimeException e) {
        if (redisUsable()) {
            log.warn("Redis unavailable ({}); serving from Postgres for {} ms", e.getClass().getSimpleName(), BREAKER_OPEN_MILLIS);
        }
        redisSkipUntil = System.currentTimeMillis() + BREAKER_OPEN_MILLIS;
        metrics.counter("pulse.cache.redis.errors").increment();
    }

    /** ±10 % random TTL. ThreadLocalRandom: a fast random generator, one per thread (no contention). */
    static Duration jitter(Duration base) {
        long ms = base.toMillis();
        long delta = (long) (ms * 0.10);
        return Duration.ofMillis(ms - delta + ThreadLocalRandom.current().nextLong(2 * delta + 1));
    }

    private void count(String cacheName, String result) {
        metrics.counter("pulse.cache.requests", "cache", cacheName, "result", result).increment();
    }
}
