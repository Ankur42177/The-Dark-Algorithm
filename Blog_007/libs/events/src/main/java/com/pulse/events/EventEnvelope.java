package com.pulse.events;

import java.time.Instant;
import java.util.UUID;

/**
 * CloudEvents-style envelope around every Kafka event (PLAN §9.1).
 *
 * <pre>
 * { "id": "0b8e…", "type": "content.post.published", "source": "content-service",
 *   "time": "2026-11-21T06:30:00Z", "schemaVersion": 1, "data": { … } }
 * </pre>
 *
 * Rules: events are FACTS in the past tense and never change. {@code id} is unique per event, so
 * consumers can detect duplicates (at-least-once delivery means duplicates WILL happen).
 * Schema evolution: only add optional fields; breaking changes bump {@code schemaVersion}.
 */
public record EventEnvelope<T>(UUID id, String type, String source, Instant time, int schemaVersion, T data) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static <T> EventEnvelope<T> of(String type, String source, T data) {
        return new EventEnvelope<>(UUID.randomUUID(), type, source, Instant.now(), CURRENT_SCHEMA_VERSION, data);
    }
}
