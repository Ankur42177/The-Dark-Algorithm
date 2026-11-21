package com.pulse.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Builds per-route rate-limit filters on top of Spring Cloud Gateway's {@link RedisRateLimiter}.
 *
 * <p>Why a DISTRIBUTED limiter? With 3 gateway replicas behind a load balancer, an in-memory counter per
 * replica would allow 3× the limit. RedisRateLimiter runs a small Lua script INSIDE Redis that reads,
 * refills and decrements the bucket atomically, so all replicas share one bucket per client.
 *
 * <p>Fail-open: if Redis errors, RedisRateLimiter answers "allowed" (and we log). Limiting is protection,
 * not a reason to take the whole API down. A 5-second circuit breaker then skips Redis entirely, so a dead
 * Redis doesn't add a failing network call to every request (measured: p95 24 ms → see Phase 5 notes).
 *
 * <p>We wrap it ourselves (instead of the stock RequestRateLimiter filter) to answer 429 with an
 * RFC 9457 Problem Details body and a Retry-After header.
 */
@Component
public class RateLimiting {

    private static final Logger log = LoggerFactory.getLogger(RateLimiting.class);

    private final RedisRateLimiter limiter;
    private final ClientIpResolver ips;
    private final int multiplier;
    /** Circuit breaker: after a Redis error, skip rate limiting until this time (epoch ms). */
    private volatile long skipUntil = 0;

    public RateLimiting(RedisRateLimiter limiter, ClientIpResolver ips, GatewayProps props) {
        this.limiter = limiter;
        this.ips = ips;
        this.multiplier = Math.max(1, props.rateLimitMultiplier());
        if (multiplier > 1) {
            log.warn("Rate limits multiplied by {} (load-test mode)", multiplier);
        }
        for (RateLimitPolicy p : RateLimitPolicy.values()) {
            limiter.getConfig().put(p.id(), p.toConfig(multiplier));   // config is looked up by "route id" = policy id
        }
    }

    public GatewayFilter filter(RateLimitPolicy policy) {
        return (exchange, chain) -> {
            if (System.currentTimeMillis() < skipUntil) {
                return chain.filter(exchange);                  // breaker open: don't even try Redis
            }
            // Bucket key = policy + client IP, so the login bucket and the public bucket are separate.
            String key = policy.id() + ":" + ips.resolve(exchange);
            return limiter.isAllowed(policy.id(), key).flatMap(response -> {
                if ("-1".equals(response.getHeaders().get("X-RateLimit-Remaining"))) {
                    // RedisRateLimiter reports -1 when Redis failed (it already allowed the request = fail-open).
                    // Open the breaker for 5 s so we don't pay a failing Redis call on every request.
                    if (System.currentTimeMillis() >= skipUntil) {
                        log.warn("Redis rate limiter unavailable: allowing traffic without limits for 5 s");
                    }
                    skipUntil = System.currentTimeMillis() + 5_000;
                }
                exposeRemaining(exchange, policy, response.getHeaders().get("X-RateLimit-Remaining"));
                if (response.isAllowed()) {
                    return chain.filter(exchange);
                }
                log.info("Rate limit '{}' exceeded for {}", policy.id(), key);
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(policy.retryAfterSeconds(multiplier)));
                return Problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                        "Rate limit '" + policy.id() + "' exceeded. Retry after " + policy.retryAfterSeconds(multiplier) + " s.");
            });
        };
    }

    /**
     * X-RateLimit-Remaining = how many more REQUESTS (not tokens) fit. With several policies on one route,
     * keep the smallest number — the limit you'll hit first.
     */
    private static void exposeRemaining(org.springframework.web.server.ServerWebExchange exchange, RateLimitPolicy policy, String tokens) {
        if (tokens == null || tokens.startsWith("-")) {
            return;                                              // -1 = Redis unavailable (fail-open)
        }
        long requests = Long.parseLong(tokens) / policy.requestedTokens;
        String current = exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining");
        if (current == null || requests < Long.parseLong(current)) {
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(requests));
        }
    }

    /** Several limits on one route (e.g. 10/min AND 100/day) = several filters in a row. */
    public GatewayFilter[] filters(RateLimitPolicy... policies) {
        GatewayFilter[] fs = new GatewayFilter[policies.length];
        for (int i = 0; i < policies.length; i++) {
            fs[i] = filter(policies[i]);
        }
        return fs;
    }
}
