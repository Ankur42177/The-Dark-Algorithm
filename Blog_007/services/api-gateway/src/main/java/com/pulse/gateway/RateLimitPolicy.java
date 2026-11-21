package com.pulse.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

/**
 * A TOKEN BUCKET: holds at most {@code burstCapacity} tokens, refills {@code replenishRate} tokens per
 * second, and each request costs {@code requestedTokens}. Redis' RedisRateLimiter only refills in whole
 * tokens per second, so slow limits are expressed with expensive requests:
 * <pre>
 *   public     60/min    : rate 1/s, burst 60,     cost 1    → 60 at once, then 1 per second
 *   login      5/15 min  : rate 1/s, burst 900,    cost 180  → 5 at once, then 1 per 3 minutes
 *   chat       10/min    : rate 1/s, burst 60,     cost 6    → 10 at once, then 1 per 6 seconds
 *   chat-day   100/day   : rate 1/s, burst 86400,  cost 864  → 100 per day
 *   admin      300/min   : rate 5/s, burst 300,    cost 1
 *   forms      5/10 min  : rate 1/s, burst 600,    cost 120  → 5 at once, then 1 per 2 minutes
 * </pre>
 * FORMS protects the public newsletter + contact forms: a human submits them once or twice; a script that
 * submits hundreds is either spamming the owner's inbox or using our confirm email to spam someone else.
 */
public enum RateLimitPolicy {
    PUBLIC(1, 60, 1),
    LOGIN(1, 900, 180),
    CHAT_MINUTE(1, 60, 6),
    CHAT_DAY(1, 86_400, 864),
    ADMIN(5, 300, 1),
    FORMS(1, 600, 120);

    final int replenishRate;
    final long burstCapacity;
    final int requestedTokens;

    RateLimitPolicy(int replenishRate, long burstCapacity, int requestedTokens) {
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
        this.requestedTokens = requestedTokens;
    }

    /** @param multiplier 1 = the limits above; e.g. 10000 for a load test that still exercises Redis on every request */
    RedisRateLimiter.Config toConfig(int multiplier) {
        int m = Math.max(1, multiplier);
        return new RedisRateLimiter.Config()
                .setReplenishRate(replenishRate * m)
                .setBurstCapacity(burstCapacity * m)
                .setRequestedTokens(requestedTokens);
    }

    /** Seconds until one more request fits, once the bucket is empty: cost / refill rate. */
    long retryAfterSeconds(int multiplier) {
        return (long) Math.ceil((double) requestedTokens / (replenishRate * Math.max(1, multiplier)));
    }

    /** Lower-case id used as the config key and as part of the Redis key. */
    String id() {
        return name().toLowerCase().replace('_', '-');
    }
}
