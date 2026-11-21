package com.pulse.ingestion.source;

import java.time.Instant;
import java.util.UUID;

public record FeedSource(UUID id, String name, String siteUrl, String feedUrl, String section, int fetchIntervalMin,
                         String etag, String lastModified, Instant lastFetchedAt, Instant nextFetchAt,
                         int consecutiveFailures, boolean active, String disabledReason) {
}
