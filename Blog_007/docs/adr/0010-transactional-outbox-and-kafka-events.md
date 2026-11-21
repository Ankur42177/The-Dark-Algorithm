# 0010. Transactional outbox + Kafka for post events
Date: 2026-11-21 · Status: Accepted

## Context
When a post changes, several things must follow: Redis eviction, website revalidation, and (Phase 8)
re-embedding for the assistant. Writing to Postgres and Kafka separately is the "dual write" problem:
a crash between the two loses the event or announces a change that was rolled back.

## Options considered
1. **Call everything synchronously after commit** (Phase 4: evict after commit). Simple, but the admin
   request waits for every downstream system, and a crash after commit loses the follow-ups.
2. **Publish to Kafka inside the request, then commit.** Kafka has no shared transaction with Postgres:
   rollback after send leaves a phantom event; failure after commit loses it.
3. **Transactional outbox + polling relay.** Event row written in the same DB transaction; a relay
   (FOR UPDATE SKIP LOCKED, batches of 100, every 250 ms) sends rows with acks=all + idempotent
   producer and marks them published. At-least-once; consumers dedupe by event id.
4. **Outbox + Debezium CDC** (read the WAL). No polling, lower latency, but another platform to run (stretch goal).

## Decision
Option 3. Topic `content.post.events` (key = postId, 3 partitions locally / 6 in k8s, RF 1 / 3),
CloudEvents-style envelope in `libs/events`, consumer group `content-cache-sync` (evict Redis + call
`/api/revalidate`), `processed_events` ledger, retries 0.5/1/2 s then `*.DLT`; bad JSON goes straight to the DLT.

## Measured
Publish → new post visible on `/tech`: **0.36–0.75 s** (3 runs, local stack) vs target < 5 s.
Tests: atomic outbox (rollback leaves no row), relay publishes keyed events, duplicate delivery processed
once, poison message → DLT while the next message on the same partition is still processed, same group
splits partitions / different groups each get everything.

## Consequences
- Good: admin requests only pay for one DB transaction; downstream work is decoupled and replayable.
- Bad: reader caches are now **eventually** consistent (sub-second normally; if Kafka is down, until it's back
  or TTLs expire). Admin reads bypass caches, so the author always sees their own writes.
- Follow-ups: Debezium; Avro + Schema Registry; consumer-lag alert (Phase 9); KEDA on lag (Phase 10).
