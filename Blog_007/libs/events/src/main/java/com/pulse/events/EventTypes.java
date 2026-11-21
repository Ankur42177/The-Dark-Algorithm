package com.pulse.events;

/** Every event type and topic name in one place. */
public final class EventTypes {

    private EventTypes() {
    }

    // ---- topics
    /** Key = postId → all events of one post land in one partition → processed in order. */
    public static final String TOPIC_POST_EVENTS = "content.post.events";
    /** Key = sourceId → articles of one feed are ordered; different feeds spread across partitions. */
    public static final String TOPIC_ARTICLE_EVENTS = "ingestion.article.events";
    /** Suffix of dead-letter topics (poison messages end up here after retries). */
    public static final String DLT_SUFFIX = ".DLT";

    // ---- event types
    public static final String POST_PUBLISHED = "content.post.published";
    public static final String POST_UPDATED = "content.post.updated";
    public static final String POST_UNPUBLISHED = "content.post.unpublished";
    public static final String ARTICLE_INGESTED = "ingestion.article.ingested";
}
