package com.pulse.ingestion.queue;

import java.time.Instant;
import java.util.UUID;

/** A JOB on the RabbitMQ work queue: "fetch this feed". PLAN §9.2: { messageId, sourceId, attempt, requestedAt }. */
public record FetchFeedMessage(UUID messageId, UUID sourceId, int attempt, Instant requestedAt) {

    public static FetchFeedMessage first(UUID sourceId) {
        return new FetchFeedMessage(UUID.randomUUID(), sourceId, 1, Instant.now());
    }

    /** Same job, next attempt (same messageId so all attempts can be traced together). */
    public FetchFeedMessage nextAttempt() {
        return new FetchFeedMessage(messageId, sourceId, attempt + 1, requestedAt);
    }
}
