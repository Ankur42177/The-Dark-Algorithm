# 0011. RabbitMQ work queue for feed fetching
Date: 2026-11-21 · Status: Accepted

## Context
ingestion-service must fetch ~20 external RSS/Atom feeds on a schedule (each every 15–60 min), retry
transient failures with backoff, isolate feeds that keep failing, and let fetching scale independently of
scheduling (0..N workers, KEDA in Phase 10). A fetch is a *job* (do it once, by anyone), not a *fact*:
Kafka (ADR-0010) stays for the resulting `ingestion.article.ingested` events.

## Options considered
1. **One `@Scheduled` loop that fetches every feed itself.** Simplest; but one slow feed delays all
   others, fetching can't scale separately, and retry state lives in memory.
2. **Database job table + `FOR UPDATE SKIP LOCKED` polling** (like the outbox relay). No new broker; but
   no push delivery, no prefetch/back-pressure, delays and dead-lettering are hand-written, and KEDA would
   need a custom query scaler.
3. **Kafka (retry topics or share groups).** Possible, but jobs aren't events, per-message delays are
   awkward, and the plan wants both messaging models learned.
4. **RabbitMQ work queue with TTL retry queues and a DLQ.** Competing consumers, manual acks, prefetch,
   per-message routing; delays from queue TTL + dead-letter exchange; first-class KEDA trigger.
5. **RabbitMQ delayed-message plugin** for retries. One queue for any delay, but a non-default plugin
   whose delayed messages aren't replicated.

## Decision
Option 4, one codebase with two Spring profiles:
- **scheduler** (2 replicas): every 60 s, ShedLock (`enqueue-due-feeds`, lockAtMostFor 50 s,
  lockAtLeastFor 5 s, Postgres provider with DB time) + atomic `UPDATE feed_sources … RETURNING id`
  claim → one `FetchFeed {messageId, sourceId, attempt, requestedAt}` per due source. Also serves the admin API.
- **worker** (0..N): `@RabbitListener` on `feed.fetch.q`, manual ack, prefetch 10, concurrency 2–4,
  graceful shutdown.
- Topology (declared at startup): direct exchange `ingestion.x`; **quorum** queue `feed.fetch.q`;
  classic `feed.fetch.retry.1..4` with queue TTL 30 s / 2 min / 10 min / 30 min that dead-letter back to
  `ingestion.x`/`feed.fetch`; **quorum** `feed.fetch.dlq` (7-day TTL) plus a `dead_letters` table for the
  admin UI (a queue can't be browsed without consuming it) with one-shot requeue.
- **One retry queue per delay**, because RabbitMQ expires messages only at the head of a queue: per-message
  TTLs in one queue would let a 30-min message block a 30-s one.
- Failed job → worker republishes a copy with `attempt + 1` to retry queue N, then acks the original.
  Transient (timeout, 5xx, 429, 408, network, Kafka down) → retry until attempt 5; permanent (SSRF block,
  4xx, not a feed, too large, too many redirects) or attempt 5 → DLQ. Unreadable JSON → `basicReject`, dropped.
- Retries are owned **only** by this layer: the HTTP client's automatic retries are disabled (ADR-0012).
- Per-source circuit breaker: 10 consecutive failed attempts → source `active = false` + `disabled_reason`.
- Admin "fetch now" / DLQ "requeue" on a paused source → **409** (the worker skips paused sources, so a
  202 would promise work that never happens).

## Measured
- `IngestionPipelineIT` (real RabbitMQ/Kafka/Postgres/Redis + WireMock): a 503 feed is requested exactly
  **5** times and dead-lettered with `attempts = 5` (it was 10 before HttpClient's hidden retry was
  disabled); an SSRF redirect is dead-lettered after **1** attempt; a second scheduler call within 5 s is
  skipped by ShedLock.
- `IngestionAdminIT`: requeue works exactly once (second click → 404); paused source → 409.
- First real run: 15 feeds enqueued, 1,272 articles, 0 duplicates; one oversize feed isolated in the DLQ.

## Consequences
- Good: slow/broken feeds are isolated per message; fetching scales on queue depth (KEDA `QueueLength` 20,
  0–10 replicas); failures are visible and replayable.
- Bad: one more broker to operate; delivery is at-least-once, so every step must be idempotent (ADR-0013);
  "publish retry copy" and "ack original" are not atomic (a crash may duplicate a job — harmless here).
- Queue arguments are immutable: changing retry delays requires deleting/renaming the retry queues.
- No jitter on retry delays (all messages in a level wait the same time); acceptable at this scale.
- Follow-ups: publisher confirms (ack the original only after the retry copy is confirmed); DLQ-depth
  alert (Phase 9); 3-node RabbitMQ in Kubernetes; compare with Kafka share groups (stretch goal).
