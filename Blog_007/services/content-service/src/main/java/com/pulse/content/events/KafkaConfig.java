package com.pulse.content.events;

import com.pulse.events.EventTypes;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * Topics + error handling for every @KafkaListener in content-service.
 */
@Configuration
public class KafkaConfig {

    /**
     * Topics are created explicitly (auto-creation is off in our broker). Partition count = the maximum
     * number of consumers in ONE group that can work in parallel. Hard to change later (it reshuffles keys).
     */
    @Bean
    NewTopic postEventsTopic(@Value("${pulse.kafka.partitions:3}") int partitions, @Value("${pulse.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(EventTypes.TOPIC_POST_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic postEventsDlt(@Value("${pulse.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(EventTypes.TOPIC_POST_EVENTS + EventTypes.DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }

    @Bean
    NewTopic articleEventsTopic(@Value("${pulse.kafka.partitions:3}") int partitions, @Value("${pulse.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(EventTypes.TOPIC_ARTICLE_EVENTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic articleEventsDlt(@Value("${pulse.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(EventTypes.TOPIC_ARTICLE_EVENTS + EventTypes.DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }

    /**
     * RETRY then DEAD-LETTER. A failing message is retried after 0.5 s, 1 s, 2 s (exponential backoff).
     * Still failing → published to "&lt;topic&gt;.DLT" and the consumer moves on, so one poison message can't
     * block its partition forever. Bad JSON is never going to succeed → no retries, straight to the DLT.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + EventTypes.DLT_SUFFIX, -1)); // -1 = let Kafka pick
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxAttempts(3);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
