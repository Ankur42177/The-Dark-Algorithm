package com.pulse.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of content.post.* events. Carries enough for consumers to act WITHOUT calling back
 * content-service (cache eviction, page revalidation, embedding in Phase 8).
 *
 * @param status the post's status after the change (PUBLISHED, UNPUBLISHED, DRAFT, SCHEDULED, DELETED)
 */
public record PostEventData(UUID postId, String slug, String section, String title, String summary,
                            List<String> tags, String status, Instant publishedAt) {
}
