package com.pulse.content.feed;

import com.pulse.content.cache.PostCache;
import com.pulse.content.events.ProcessedEvents;
import com.pulse.content.events.RevalidateClient;
import com.pulse.content.post.Section;
import com.pulse.events.ArticleIngestedData;
import com.pulse.events.EventEnvelope;
import com.pulse.events.EventTypes;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer group {@code content-feed-readmodel}: builds feed_items from ingestion.article.events.
 *
 * <p>Here the side effect IS a database write, so "record the event id" and "upsert the row" happen in ONE
 * transaction: either both or neither → effectively exactly-once, even with at-least-once delivery.
 *
 * <p>Ingestion produces bursts (dozens of articles in a second). Instead of calling the website once per
 * article, we collect the touched sections and refresh them every 5 s ("debouncing").
 */
@Component
public class FeedReadModelConsumer {

    static final String GROUP = "content-feed-readmodel";
    private static final Logger log = LoggerFactory.getLogger(FeedReadModelConsumer.class);
    private static final TypeReference<EventEnvelope<ArticleIngestedData>> TYPE = new TypeReference<>() { };

    private final JsonMapper json;
    private final FeedRepository repo;
    private final ProcessedEvents processed;
    private final PostCache cache;
    private final RevalidateClient web;
    private final MeterRegistry metrics;
    private final Set<String> dirtySections = ConcurrentHashMap.newKeySet();

    public FeedReadModelConsumer(JsonMapper json, FeedRepository repo, ProcessedEvents processed, PostCache cache,
                                 RevalidateClient web, MeterRegistry metrics) {
        this.json = json;
        this.repo = repo;
        this.processed = processed;
        this.cache = cache;
        this.web = web;
        this.metrics = metrics;
    }

    @KafkaListener(topics = EventTypes.TOPIC_ARTICLE_EVENTS, groupId = GROUP)
    @Transactional
    public void onArticle(ConsumerRecord<String, String> record) {
        EventEnvelope<ArticleIngestedData> event = json.readValue(record.value(), TYPE);
        ArticleIngestedData a = event.data();
        if (event.id() == null || a == null || a.urlHash() == null || a.title() == null || a.section() == null) {
            throw new IllegalArgumentException("Incomplete ArticleIngested event at offset " + record.offset());
        }
        if (!processed.markProcessed(event.id(), GROUP)) {        // insert-first: same transaction as the upsert
            metrics.counter("pulse.events.duplicates", "group", GROUP).increment();
            return;
        }
        repo.upsert(a);
        dirtySections.add(Section.canonical(a.section()));
        metrics.counter("pulse.events.processed", "group", GROUP, "type", event.type()).increment();
    }

    /** Debounced: bump the feed cache generation and refresh affected pages at most every 5 s. */
    @Scheduled(fixedDelayString = "${pulse.feed.revalidate-interval:5s}")
    public void flushRevalidations() {
        if (dirtySections.isEmpty()) {
            return;
        }
        List<String> sections = new ArrayList<>(dirtySections);
        dirtySections.removeAll(sections);
        cache.bumpGeneration(FeedService.FEED_GENERATION);
        List<String> tags = new ArrayList<>(List.of("feed", "feed:home"));
        sections.forEach(s -> tags.add("feed:" + s));
        try {
            web.revalidate(tags);
            log.info("Feed updated for {} → website revalidated", sections);
        } catch (RuntimeException e) {
            dirtySections.addAll(sections);                       // try again on the next tick
            log.warn("Website revalidation failed ({}); will retry", e.getClass().getSimpleName());
        }
    }
}
