# 0009. Edge rate limiting: Redis token buckets keyed by client IP, fail-open
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §7.1: public API 60 req/min/IP, login 5 attempts/15 min/IP, chat 10 msgs/min/IP and 100/day/IP.
Gateways run as several replicas, so counters must be shared.

## Options considered
1. **In-memory per replica** — simple; N replicas allow N× the limit; restarts reset counters.
2. **Redis fixed window (INCR + EXPIRE)** — simple, but allows 2× bursts at window boundaries.
3. **Redis sliding-window log (sorted set of timestamps)** — exact, but memory grows with traffic.
4. **Redis token bucket (Spring Cloud Gateway `RedisRateLimiter`, Lua)** — smooth, allows short bursts, O(1) memory per client, atomic.

## Decision
Option 4. Policies (`RateLimitPolicy`): rate refills 1 token/s and request cost expresses slow limits —
public 60/min (burst 60, cost 1), login 5/15 min (burst 900, cost 180), chat 10/min (burst 60, cost 6) **and**
100/day (burst 86 400, cost 864), admin 300/min. Redis key = `{policy}:{client-ip}`.
429 responses are RFC 9457 Problem Details with `Retry-After` (cost ÷ refill rate). `X-RateLimit-Remaining`
reports requests (not tokens) left in the tightest bucket.

Client identity: the TCP peer address. At the edge (`trusted-proxy-hops: 0`) incoming `X-Forwarded-For`/`Forwarded`
headers are stripped (spoofable); behind an ingress (Phase 10) set `trusted-proxy-hops: 1`.
**Fail-open**: if Redis is unavailable, requests are allowed (logged) rather than rejecting all traffic.

Measured (k6, 1 client, 80 requests): 60 × 200 then 20 × 429, all with Retry-After and Problem Details.

## Consequences
- Good: limits hold across replicas; login brute force is throttled twice (gateway + content-service).
- Bad: IP-based limits punish users behind one NAT and let botnets through; IPv4 and IPv6 of the same machine
  are different keys. All server-side Next.js fetches share one IP (127.0.0.1) and one public bucket.
- Follow-up: an internal-client policy (signed header from the web server) with a larger bucket; per-user limits
  for authenticated routes; a load-test multiplier (`PULSE_RATE_LIMIT_MULTIPLIER`) exists for benchmarks only.
