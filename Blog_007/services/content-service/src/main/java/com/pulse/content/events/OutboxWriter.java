package com.pulse.content.events;

import com.pulse.content.post.Post;
import com.pulse.content.post.Tag;
import com.pulse.events.EventEnvelope;
import com.pulse.events.EventTypes;
import com.pulse.events.PostEventData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Appends events to the outbox table. {@code Propagation.MANDATORY}: calling it WITHOUT an active
 * transaction is a bug (the whole point is to share the business change's transaction), so Spring
 * throws immediately instead of silently running in its own transaction.
 */
@Component
public class OutboxWriter {

    private static final String SOURCE = "content-service";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Record a post event (published / updated / unpublished) for the post's current state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postEvent(String type, Post p, String statusOverride) {
        PostEventData data = new PostEventData(p.getId(), p.getSlug(), p.getSection().name(), p.getTitle(), p.getSummary(),
                p.getTags().stream().map(Tag::getName).sorted().toList(),
                statusOverride != null ? statusOverride : p.getStatus().name(), p.getPublishedAt());
        return append(EventTypes.TOPIC_POST_EVENTS, p.getId(), EventEnvelope.of(type, SOURCE, data));
    }

    /** Variant for the scheduled publisher, which only has columns from an UPDATE … RETURNING. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postPublished(UUID postId, String slug, String section, String title, String summary, List<String> tags, Instant publishedAt) {
        PostEventData data = new PostEventData(postId, slug, section, title, summary, tags, "PUBLISHED", publishedAt);
        return append(EventTypes.TOPIC_POST_EVENTS, postId, EventEnvelope.of(EventTypes.POST_PUBLISHED, SOURCE, data));
    }

    private UUID append(String topic, UUID aggregateId, EventEnvelope<?> event) {
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, topic, event_type, payload)
                        VALUES (:id, :agg, :topic, :type, CAST(:payload AS jsonb))""")
                .param("id", event.id())
                .param("agg", aggregateId)
                .param("topic", topic)
                .param("type", event.type())
                .param("payload", json.writeValueAsString(event))
                .update();
        return event.id();
    }
}
