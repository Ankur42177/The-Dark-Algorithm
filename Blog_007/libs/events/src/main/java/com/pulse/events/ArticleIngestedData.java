package com.pulse.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of ingestion.article.ingested: METADATA ONLY (PLAN §5.1 fair-use rules) — title, link, author,
 * date, source and an excerpt of at most 300 characters. Never the article body.
 *
 * @param urlHash SHA-256 of the canonical URL: the natural idempotency key for consumers
 */
public record ArticleIngestedData(UUID sourceId, String sourceName, String section, String url, String urlHash,
                                  String title, String excerpt, String author, String imageUrl, Instant publishedAt) {
}
