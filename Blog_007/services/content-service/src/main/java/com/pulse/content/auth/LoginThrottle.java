package com.pulse.content.auth;

import com.pulse.content.cache.PostCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Brute-force protection: max 5 failed logins per IP per 15 minutes (PLAN §15), counted in Redis
 * ("fixed window" counter: INCR + EXPIRE). The gateway (Phase 5) adds a second, coarser limit.
 * If Redis is down we fail OPEN (allow), logged — BCrypt's slowness still limits guessing speed.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final PostCache breaker;

    public LoginThrottle(StringRedisTemplate redis, PostCache breaker) {
        this.redis = redis;
        this.breaker = breaker;
    }

    public boolean isBlocked(String ip) {
        if (!breaker.redisUsable()) {
            return false;
        }
        try {
            String v = redis.opsForValue().get(key(ip));
            return v != null && Integer.parseInt(v) >= MAX_FAILURES;
        } catch (RuntimeException e) {
            breaker.tripBreaker(e);
            return false;
        }
    }

    public void recordFailure(String ip) {
        if (!breaker.redisUsable()) {
            return;
        }
        try {
            Long n = redis.opsForValue().increment(key(ip));
            if (n != null && n == 1) {
                redis.expire(key(ip), WINDOW);     // the window starts at the first failure
            }
        } catch (RuntimeException e) {
            breaker.tripBreaker(e);
        }
    }

    public void reset(String ip) {
        if (breaker.redisUsable()) {
            try {
                redis.delete(key(ip));
            } catch (RuntimeException e) {
                breaker.tripBreaker(e);
            }
        }
    }

    private static String key(String ip) {
        return "auth:fail:" + ip;
    }
}
