package com.pulse.assistant.embed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The EMBEDDER ROLE: only active with the "embedder" Spring profile, so the api pods never consume.
 * Consumer group "assistant-embedder" gets its OWN copy of every event (other groups, like content-cache-sync,
 * get theirs). Scaled by KEDA on consumer lag (Phase 10), at most one consumer per partition.
 */
@Component
@Profile("embedder")
public class KafkaEmbedderListener {

    private final EventHandler handler;

    public KafkaEmbedderListener(EventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = {"content.post.events", "ingestion.article.events"}, groupId = "assistant-embedder",
            autoStartup = "${pulse.embedder.kafka-enabled:true}")
    public void onEvent(String payload) {
        handler.handle(payload);       // an exception → retried with backoff, then sent to <topic>.DLT
    }

    @Configuration
    @Profile("embedder")
    static class ErrorHandling {
        /**
         * Retries: 1 s, 2 s, 4 s (embedding model briefly down?), then the poison message goes to the
         * Dead Letter Topic "<topic>.DLT" so it can't block the partition forever.
         */
        @Bean
        DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
            ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
            backOff.setMaxAttempts(3);
            return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
        }
    }
}
