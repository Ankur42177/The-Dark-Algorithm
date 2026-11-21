package com.pulse.content.cache;

import com.pulse.content.post.Section;

/**
 * Every Redis key content-service uses, in one place (PLAN §10). Prefixes make keys easy to find
 * with SCAN and impossible to mix up between features.
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** Bumped (INCR) whenever any post changes; part of every list key. */
    public static final String LIST_GENERATION = "cache:gen:lists";

    public static String post(String slug) {
        return "cache:post:" + slug;
    }

    public static String list(long generation, Section section, String tag, String cursor, int limit) {
        return "cache:list:" + generation + ":" + (section == null ? "ALL" : section.name()) + ":"
                + (tag == null ? "-" : tag) + ":" + (cursor == null ? "first" : cursor) + ":" + limit;
    }

    public static String views(String slug) {
        return "views:" + slug;
    }

    public static final String VIEWS_DIRTY = "views:dirty";

    public static String trending(Section section) {
        return "trending:" + (section == null ? "ALL" : section.name());
    }

    public static final String TRENDING_DECAY_LOCK = "lock:trending-decay";
}
