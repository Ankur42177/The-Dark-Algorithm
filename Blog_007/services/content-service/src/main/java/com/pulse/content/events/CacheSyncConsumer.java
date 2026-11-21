package com.pulse.content.events;

import com.pulse.content.post.PostService;
import com.pulse.events.EventEnvelope;
import com.pulse.events.EventTypes;
import com.pulse.events.PostEventData;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumer group {@code content-cache-sync}: when a post changes, evict Redis and refresh the website.
 *
 * <p>Consumer GROUP = a team of consumers sharing the work: each partition of the topic is assigned to
 * exactly one member. Another group (e.g. assistant-embedder in Phase 8) gets its OWN copy of every event.
 *
 * <p>Delivery is at-least-once, so this consumer is IDEMPOTENT: {@link ProcessedEvents} remembers event ids.
 * Our side effects (DEL a key, revalidate a page) are harmless to repeat anyway — the table makes the
 * pattern explicit and lets us count duplicates.
 */
@Component
public class CacheSyncConsumer {

    static final String GROUP = "content-cache-sync";
    private static final Logger log = LoggerFactory.getLogger(CacheSyncConsumer.class);
    private static final TypeReference<EventEnvelope<PostEventData>> TYPE = new TypeReference<>() { };

    private final JsonMapper json;
    private final PostService posts;
    private final RevalidateClient web;
    private final ProcessedEvents processed;
    private final MeterRegistry metrics;

    public CacheSyncConsumer(JsonMapper json, PostService posts, RevalidateClient web, ProcessedEvents processed, MeterRegistry metrics) {
        this.json = json;
        this.posts = posts;
        this.web = web;
        this.processed = processed;
        this.metrics = metrics;
    }

    @KafkaListener(topics = EventTypes.TOPIC_POST_EVENTS, groupId = GROUP)
    public void onPostEvent(ConsumerRecord<String, String> record) {
        // Malformed JSON throws here → not retryable → straight to content.post.events.DLT (see KafkaConfig).
        EventEnvelope<PostEventData> event = json.readValue(record.value(), TYPE);
        if (event.id() == null || event.data() == null || event.data().slug() == null) {
            throw new IllegalArgumentException("Event without id/data/slug at offset " + record.offset());
        }
        if (processed.alreadyProcessed(event.id(), GROUP)) {
            metrics.counter("pulse.events.duplicates", "group", GROUP).increment();
            log.debug("Duplicate event {} skipped", event.id());
            return;
        }
        PostEventData d = event.data();
        posts.evict(d.slug());                                       // Redis: post key + list generation
        List<String> tags = new ArrayList<>(List.of("post:" + d.slug(), "home", "posts"));
        if (d.section() != null) {
            tags.add("section:" + d.section());
        }
        web.revalidate(tags);                                        // website: rebuild affected pages
        processed.markProcessed(event.id(), GROUP);                  // only after the side effects succeeded
        metrics.counter("pulse.events.processed", "group", GROUP, "type", event.type()).increment();
        log.info("{} {} → cache evicted, pages revalidated (partition {}, offset {})",
                event.type(), d.slug(), record.partition(), record.offset());
    }
}
