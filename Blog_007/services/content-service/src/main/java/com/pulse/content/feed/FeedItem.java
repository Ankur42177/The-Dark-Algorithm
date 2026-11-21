package com.pulse.content.feed;

import com.pulse.content.post.Section;

import java.time.Instant;
import java.util.UUID;

/** One "Around the web" entry, as returned by GET /api/v1/feed (mirrors web/src/lib/types.ts FeedItem). */
public record FeedItem(UUID id, String sourceName, String url, String title, String excerpt, String author,
                       Section section, String imageUrl, Instant publishedAt) {
}
