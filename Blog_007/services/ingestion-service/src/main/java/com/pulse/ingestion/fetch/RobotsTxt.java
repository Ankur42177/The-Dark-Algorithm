package com.pulse.ingestion.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Respect robots.txt (PLAN §5.1): before fetching a feed, check the site's rules for our User-Agent
 * ("PulseBot") or "*". The parsed Disallow list is cached in Redis for 24 h (one robots.txt request per
 * site per day). Missing/unreachable robots.txt = allowed (the usual convention).
 */
@Component
public class RobotsTxt {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxt.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final SafeFetcher fetcher;
    private final StringRedisTemplate redis;

    public RobotsTxt(SafeFetcher fetcher, StringRedisTemplate redis) {
        this.fetcher = fetcher;
        this.redis = redis;
    }

    public boolean isAllowed(String feedUrl) {
        URI uri = URI.create(feedUrl);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String key = "robots:" + origin;
        String rules = null;
        try {
            rules = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.debug("Redis unavailable for robots cache: {}", e.getMessage());
        }
        if (rules == null) {
            rules = String.join("\n", disallowsFor(download(origin + "/robots.txt")));
            try {
                redis.opsForValue().set(key, rules, CACHE_TTL);
            } catch (RuntimeException ignored) {
                // cache is optional
            }
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        for (String prefix : rules.split("\n")) {
            if (!prefix.isBlank() && path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private String download(String url) {
        try {
            FetchResult r = fetcher.fetch(url, null, null);
            return new String(r.body(), StandardCharsets.UTF_8);
        } catch (FetchException e) {
            return "";                                   // no robots.txt (or unreachable) → allowed
        }
    }

    /**
     * Minimal robots.txt parser. A group = one or more "User-agent:" lines followed by rules.
     * If a group names PulseBot we obey it; otherwise we obey the "*" group.
     */
    static List<String> disallowsFor(String robots) {
        List<String> mine = new ArrayList<>(), star = new ArrayList<>();
        boolean groupMine = false, groupStar = false, readingAgents = false, sawMine = false;
        for (String raw : robots.split("\r?\n")) {
            String line = raw.replaceAll("#.*", "").trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (!readingAgents) {                 // a new group starts
                    groupMine = false;
                    groupStar = false;
                }
                readingAgents = true;
                String agent = value.toLowerCase(Locale.ROOT);
                if (agent.equals("*")) {
                    groupStar = true;
                } else if (agent.startsWith("pulsebot")) {
                    groupMine = true;
                    sawMine = true;
                }
            } else {
                readingAgents = false;
                if (field.equals("disallow") && !value.isEmpty()) {
                    if (groupMine) {
                        mine.add(value);
                    }
                    if (groupStar) {
                        star.add(value);
                    }
                }
            }
        }
        return sawMine ? mine : star;
    }
}
