# 0013. CQRS feed read model, deduplication and editorial curation
Date: 2026-11-21 · Status: Accepted

## Context
The website's "Around the web" lists must show external articles fast, from content-service, while
ingestion-service owns the raw data in `ingestion_db` (database per service). RabbitMQ and Kafka both
deliver at-least-once, the same article appears under several URLs and across runs, and the first real
run showed that a technically correct pipeline still produced a poor page (live blogs, quizzes,
podcasts, quote posts, version bumps, news-wire noise).

## Options considered
1. **content-service reads `ingestion_db` directly.** Fast to build; couples schemas and deployments,
   breaks database-per-service.
2. **content-service calls ingestion-service's API on each page view.** Couples availability and latency.
3. **CQRS-lite read model**: ingestion publishes `ingestion.article.ingested` (metadata only); content-service
   projects it into its own `feed_items` table.
4. For quality: **filter at display time** in content-service or the web, vs **filter at ingestion**
   before storing, vs **choose better sources**.

## Decision
- **Read model (option 3).** Kafka `ingestion.article.events`, key = sourceId. Consumer group
  `content-feed-readmodel` inserts into `processed_events` and upserts `feed_items`
  (`ON CONFLICT (url_hash) DO UPDATE`) **in one transaction** → effectively exactly-once. `GET /api/v1/feed`:
  keyset pagination on `(section, published_at DESC, id DESC)`, Redis cache with a generation key bumped by
  the consumer, `Cache-Control: public, max-age=60`. Website revalidation debounced to at most every 5 s.
  Malformed events → `.DLT`.
- **Deduplication.** Canonical URL (lower-case scheme/host, no fragment, no `utm_*`) → SHA-256 `url_hash`,
  the shared identity of an article in every store. Redis `dedupe:article:{hash}` (30 d) as the fast
  filter, written only after the row exists; `raw_items.url_hash UNIQUE` + `ON CONFLICT DO NOTHING` as the
  guarantee. The full event (with its id) is built once and stored in `raw_items.payload`; resends reuse
  the id.
- **Mini-outbox.** `raw_items.published_at IS NULL` = not yet announced; `publishPending` sends up to 500
  rows `FOR UPDATE SKIP LOCKED`, waits for all acks, then marks them; leftovers go out on the next run
  (also on 304 runs).
- **Fair use.** Events and `feed_items` carry metadata only: title, link, author, date, image URL,
  excerpt ≤ 300 chars (CHECK constraint). Always link out ("Read on {source}").
- **Quality filter at ingestion (before storing).** `QualityFilter`: named regex rules on titles —
  `live-blog`, `quiz`, `podcast`, `video-link`, `quote-post`, `release-note` — so a rejected item is never
  stored, published, shown or embedded; `reject()` returns the rule name; tested with real titles and
  near-misses (`QualityFilterTest`).
- **Curation as Flyway migrations** (reviewable, identical everywhere; never edit an applied one):
  - V2 seed: every URL verified politely; StatsBomb (HTML) and Football365 (404) seeded inactive with a reason.
  - V3: BBC and Guardian football news wires paused; Simon Willison → long-form feed (validators reset);
    Raschka unblocked after the 5 MB cap (ADR-0012); football analysis sources added (Spielverlagerung,
    Jonathan Wilson).
  - **V4 (decided 2026-11-21): Tech "Around the web" = official company blogs only.** Kept Netflix
    TechBlog, Cloudflare, Spring, Hugging Face; added LangChain, Claude (Anthropic), Google AI, Google
    Research, Google DeepMind (hourly); paused 9 individual blogs (paused, not deleted, with
    `disabled_reason`); AI sources moved into TECH (the site labels the merged section "Code & Cortex").
  - Candidate football blogs whose expired domains had turned into gambling spam were rejected: a source
    is only as trustworthy as the domain's current owner.
- **Clean-up of already-stored items** (one-off `local-tools/cleanup_paused_sources.sh`, outside the repo):
  dry-run by default; read models (`content_db.feed_items`, `assistant_db.doc_chunks`) cleaned before the
  source of truth (`ingestion_db.raw_items`), matching on `url_hash`; one transaction per database,
  idempotent steps. Removed **211** items.

## Measured
- First real run: 1,272 articles, **0 duplicates**; BBC 75 items → 71 new (duplicates inside one feed);
  Hugging Face 915 items → 500 published per run (batch cap), the rest on the next run.
- `FeedReadModelIT`: the same event delivered twice → one `feed_items` row, served by `/api/v1/feed`.
- `FeedSourceCurationIT`: after V2–V4, no seeded AI sources, individual blogs paused with a reason, Tech =
  the 9 official blogs, football = the 2 analysis sources.
- Freshness: pipeline adds ~1 min (tick + fetch + Kafka + ≤ 5 s debounce); the fetch interval (mostly
  60 min, politeness) dominates.

## Consequences
- Good: services stay independent; the page reads one indexed table; ingestion can be down without the
  site noticing; every editorial drop is named and versioned.
- Bad: eventual consistency; data in up to three stores (raw, read model, embeddings) makes deletion a
  multi-step job; deleting `raw_items` also deletes the durable dedupe record (a re-enabled source could
  re-ingest old items once the Redis keys expire).
- Regex title rules need maintenance as feeds change; false positives are possible (mitigated by tests
  with near-misses).
- Follow-ups: an `ingestion.article.retracted` event so each read model deletes its own copy instead of a
  script; periodic re-verification of sources (domain ownership, still a feed); Phase 8b "Curator" agent
  for AI summaries (`feed_items.ai_summary`).
