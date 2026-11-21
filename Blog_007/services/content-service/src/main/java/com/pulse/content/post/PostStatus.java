package com.pulse.content.post;

/**
 * Lifecycle of a post: DRAFT -> SCHEDULED -> PUBLISHED -> UNPUBLISHED.
 * Readers only ever see PUBLISHED posts. The admin flow that moves posts between
 * these states arrives in Phase 4.
 */
public enum PostStatus {
    DRAFT,
    SCHEDULED,
    PUBLISHED,
    UNPUBLISHED
}
