package com.pulse.ingestion.queue;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RabbitMQ topology (PLAN §9.2), declared at startup (idempotent: existing objects are left alone).
 *
 * <pre>
 *                  routing key feed.fetch            ┌──────────────┐
 *  scheduler ──►  ingestion.x (direct) ────────────► │ feed.fetch.q │ ──► workers (manual ack, prefetch 10)
 *                    ▲     │                         └──────────────┘          │ transient failure
 *                    │     │ feed.fetch.retry.N                                ▼
 *                    │     └──► feed.fetch.retry.N (TTL 30 s / 2 m / 10 m / 30 m, no consumers)
 *                    └───────── when the TTL expires, RabbitMQ dead-letters it back to ingestion.x/feed.fetch
 *                          feed.fetch.dlq  ◄── after 5 attempts, or a permanent error
 * </pre>
 *
 * Why one retry queue PER DELAY instead of one queue with per-message TTLs? RabbitMQ only expires
 * messages at the HEAD of a queue: a 30-minute message in front would block a 30-second one behind it.
 */
@Configuration
public class RabbitTopology {

    public static final String EXCHANGE = "ingestion.x";
    public static final String FETCH_QUEUE = "feed.fetch.q";
    public static final String FETCH_KEY = "feed.fetch";
    public static final String RETRY_QUEUE_PREFIX = "feed.fetch.retry.";
    public static final String DLQ = "feed.fetch.dlq";
    public static final String DLQ_KEY = "feed.fetch.dlq";
    public static final int MAX_ATTEMPTS = 5;

    @Bean
    Declarables ingestionTopology(@Value("${pulse.ingestion.retry-delays:30s,2m,10m,30m}") List<Duration> delays) {
        List<org.springframework.amqp.core.Declarable> d = new ArrayList<>();
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        d.add(exchange);

        // QUORUM queue: replicated with Raft across RabbitMQ nodes, survives a node loss (3-node cluster in k8s).
        Queue fetch = QueueBuilder.durable(FETCH_QUEUE).quorum().build();
        d.add(fetch);
        d.add(BindingBuilder.bind(fetch).to(exchange).with(FETCH_KEY));

        for (int level = 1; level <= delays.size(); level++) {
            Queue retry = QueueBuilder.durable(RETRY_QUEUE_PREFIX + level)
                    .ttl((int) delays.get(level - 1).toMillis())        // every message waits exactly this long…
                    .deadLetterExchange(EXCHANGE)                       // …then is "dead-lettered" back
                    .deadLetterRoutingKey(FETCH_KEY)                    //    to the main queue
                    .build();
            d.add(retry);
            d.add(BindingBuilder.bind(retry).to(exchange).with(RETRY_QUEUE_PREFIX + level));
        }

        Queue dlq = QueueBuilder.durable(DLQ).quorum().ttl((int) Duration.ofDays(7).toMillis()).build();
        d.add(dlq);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(exchange).with(DLQ_KEY);
        d.add(dlqBinding);
        return new Declarables(d);
    }
}
