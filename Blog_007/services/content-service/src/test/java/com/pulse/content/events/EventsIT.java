package com.pulse.content.events;

import com.pulse.content.IntegrationTestBase;
import com.pulse.content.admin.AdminDtos.PostRequest;
import com.pulse.content.admin.AdminPostService;
import com.pulse.content.cache.CacheKeys;
import com.pulse.content.post.PostService;
import com.pulse.content.post.Section;
import com.pulse.events.EventEnvelope;
import com.pulse.events.EventTypes;
import com.pulse.events.PostEventData;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Phase 6: transactional outbox → relay → Kafka → idempotent consumer, DLT, consumer groups. */
class EventsIT extends IntegrationTestBase {

    @Autowired AdminPostService admin;
    @Autowired PostService posts;
    @Autowired OutboxWriter outbox;
    @Autowired JdbcClient jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry metrics;

    @Test
    void outboxRowIsWrittenInTheSameTransactionAsTheChange() {
        long before = count("SELECT count(*) FROM outbox");
        // A transaction that writes the outbox row and then FAILS must leave no trace at all.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE posts SET title = 'rolled back' WHERE slug = 'rag-in-one-picture'").update();
            var p = posts.getPublishedBySlug("rag-in-one-picture");
            outbox.postPublished(p.id(), p.slug(), "AI", p.title(), null, List.of(), Instant.now());
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(before);
        assertThat(jdbc.sql("SELECT title FROM posts WHERE slug = 'rag-in-one-picture'").query(String.class).single())
                .isNotEqualTo("rolled back");
        // And the writer refuses to run outside a transaction (Propagation.MANDATORY).
        assertThatThrownBy(() -> outbox.postPublished(UUID.randomUUID(), "x", "AI", "x", null, List.of(), Instant.now()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void publishGoesThroughOutboxAndKafkaAndEvictsTheCache() {
        var created = admin.create(new PostRequest("Outbox demo post", null, "s", "body", Section.TECH, List.of("kafka"), null, null));
        try (KafkaConsumer<String, String> c = consumer("test-reader-" + UUID.randomUUID())) {
            c.subscribe(List.of(EventTypes.TOPIC_POST_EVENTS));
            admin.publish(created.id());
            // relay marks the row published
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    count("SELECT count(*) FROM outbox WHERE aggregate_id = '" + created.id() + "' AND event_type = 'content.post.published' AND published_at IS NOT NULL") == 1);
            // the event is on the topic, keyed by post id, with the envelope fields
            ConsumerRecord<String, String> rec = pollUntil(c, r -> r.value().contains("Outbox demo post") && r.value().contains(EventTypes.POST_PUBLISHED));
            assertThat(rec.key()).isEqualTo(created.id().toString());
            EventEnvelope<?> env = json.readValue(rec.value(), EventEnvelope.class);
            assertThat(env.source()).isEqualTo("content-service");
            assertThat(env.schemaVersion()).isEqualTo(1);
        }
        // the content-cache-sync group processed it (idempotency ledger) and evicted Redis
        posts.getPublishedBySlug("outbox-demo-post");                              // cache it
        assertThat(redis.hasKey(CacheKeys.post("outbox-demo-post"))).isTrue();
        admin.unpublish(created.id());
        await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(CacheKeys.post("outbox-demo-post"))));
    }

    @Test
    void duplicateDeliveryIsHarmless() throws Exception {
        UUID eventId = UUID.randomUUID();
        String event = json.writeValueAsString(new EventEnvelope<>(eventId, EventTypes.POST_UPDATED, "test", Instant.now(), 1,
                new PostEventData(UUID.randomUUID(), "rag-in-one-picture", "AI", "t", null, List.of(), "PUBLISHED", Instant.now())));
        double dupesBefore = counter("pulse.events.duplicates");
        try (KafkaProducer<String, String> p = producer()) {
            p.send(new ProducerRecord<>(EventTypes.TOPIC_POST_EVENTS, "k", event)).get();
            p.send(new ProducerRecord<>(EventTypes.TOPIC_POST_EVENTS, "k", event)).get();   // the same event again
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> counter("pulse.events.duplicates") >= dupesBefore + 1);
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = '" + eventId + "'")).isEqualTo(1);
    }

    @Test
    void poisonMessageLandsInTheDeadLetterTopicAndDoesNotBlockThePartition() throws Exception {
        try (KafkaConsumer<String, String> dlt = consumer("dlt-reader-" + UUID.randomUUID());
             KafkaProducer<String, String> p = producer()) {
            dlt.subscribe(List.of(EventTypes.TOPIC_POST_EVENTS + EventTypes.DLT_SUFFIX));
            dlt.poll(Duration.ofMillis(500));
            p.send(new ProducerRecord<>(EventTypes.TOPIC_POST_EVENTS, "same-key", "this is not JSON {")).get();
            UUID goodId = UUID.randomUUID();
            p.send(new ProducerRecord<>(EventTypes.TOPIC_POST_EVENTS, "same-key", json.writeValueAsString(new EventEnvelope<>(goodId,
                    EventTypes.POST_UPDATED, "test", Instant.now(), 1,
                    new PostEventData(UUID.randomUUID(), "arsenal-pressing-numbers", "FOOTBALL", "t", null, List.of(), "PUBLISHED", Instant.now()))))).get();

            ConsumerRecord<String, String> dead = pollUntil(dlt, r -> r.value().startsWith("this is not JSON"));
            assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-fqcn").value())).contains("Exception");
            // the good message behind it on the SAME partition was still processed
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    count("SELECT count(*) FROM processed_events WHERE event_id = '" + goodId + "'") == 1);
        }
    }

    @Test
    void sameGroupSplitsTheWorkAndDifferentGroupsEachGetEverything() throws Exception {
        String topic = "groups-demo-" + UUID.randomUUID();
        try (AdminClient a = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            a.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        }
        try (var a1 = consumer("team-a"); var a2 = consumer("team-a"); var b1 = consumer("team-b");
             KafkaProducer<String, String> p = producer()) {
            a1.subscribe(List.of(topic));
            a2.subscribe(List.of(topic));
            b1.subscribe(List.of(topic));
            // Wait until the group has settled: both team-a members own partitions (3 partitions → 2 + 1).
            long settle = System.currentTimeMillis() + 30_000;
            while ((a1.assignment().isEmpty() || a2.assignment().isEmpty() || b1.assignment().isEmpty())
                    && System.currentTimeMillis() < settle) {
                a1.poll(Duration.ofMillis(100));
                a2.poll(Duration.ofMillis(100));
                b1.poll(Duration.ofMillis(100));
            }
            assertThat(a1.assignment().size() + a2.assignment().size()).isEqualTo(3);
            assertThat(b1.assignment()).hasSize(3);
            for (int i = 0; i < 30; i++) {
                p.send(new ProducerRecord<>(topic, "key-" + i, "msg-" + i));
            }
            p.flush();
            List<String> gotA1 = new ArrayList<>(), gotA2 = new ArrayList<>(), gotB1 = new ArrayList<>();
            long end = System.currentTimeMillis() + 20_000;
            while ((gotA1.size() + gotA2.size() < 30 || gotB1.size() < 30) && System.currentTimeMillis() < end) {
                a1.poll(Duration.ofMillis(200)).forEach(r -> gotA1.add(r.value()));
                a2.poll(Duration.ofMillis(200)).forEach(r -> gotA2.add(r.value()));
                b1.poll(Duration.ofMillis(200)).forEach(r -> gotB1.add(r.value()));
            }
            assertThat(gotA1.size() + gotA2.size()).isEqualTo(30);              // team A shares the 30 messages…
            assertThat(gotA1).isNotEmpty();
            assertThat(gotA2).isNotEmpty();                                     // both members worked
            assertThat(gotA1).doesNotContainAnyElementsOf(gotA2);               // …without overlap
            assertThat(gotB1).hasSize(30);                                       // team B gets its own full copy
        }
    }

    // ------------------------------------------------------------------ helpers

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private double counter(String name) {
        return metrics.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private KafkaConsumer<String, String> consumer(String group) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(p);
    }

    private KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(p);
    }

    private ConsumerRecord<String, String> pollUntil(KafkaConsumer<String, String> c, java.util.function.Predicate<ConsumerRecord<String, String>> match) {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        throw new AssertionError("no matching record within 20 s");
    }
}
