# 0006. Redis cache-aside with generation keys, single-flight and fail-open
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §10/§20 Phase 3: cache hot reads, count views, trending; site must survive Redis being down.

## Options considered
1. **Spring `@Cacheable`** — least code. Cons: hides keys/TTLs, no jitter or single-flight out of the box, error handling is global.
2. **Explicit cache-aside (`PostCache`)** — keys, TTL jitter, single-flight, circuit breaker and metrics visible in ~150 lines.
3. **No cache, only indexes + HTTP/CDN caching** — simplest. Measured: with indexes, Redis gave no p95 gain.

## Decision
Option 2, with list invalidation by **generation bump** (`INCR cache:gen:lists`) instead of SCAN/DEL,
**fail-open** (Lettuce REJECT_COMMANDS + 5 s breaker), and a `pulse.cache.enabled` switch for experiments.
Views: Redis INCR + write-behind every 60 s. Trending: sorted sets with hourly 0.9 decay under a SET NX lock.

## Measured (k6, 50 VUs, 100k rows)
Indexed: cache on 22.2k req/s p95 4.7 ms vs off 25.9k req/s p95 5.1 ms (no gain).
Indexes dropped: on 15.7k req/s p95 6.6 ms vs off 7.0k req/s p95 14.8 ms (2.2× throughput).

## Consequences
- Good: DB protected from load spikes and stampedes; outage of Redis is invisible except for trending/views.
- Bad: up to 60 s stale lists until Phase 6 event-driven eviction; extra Redis round trip for the generation.
- Follow-up: in-process L1 cache for the generation number if Redis latency matters; split cache/data Redis.
