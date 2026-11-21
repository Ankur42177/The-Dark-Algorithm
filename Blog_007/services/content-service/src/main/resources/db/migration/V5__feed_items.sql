-- =====================================================================
-- V5 (Phase 7): the READ MODEL of external articles ("Around the web").
-- Built ONLY from ingestion.article.events (CQRS-lite): content-service never
-- reads ingestion_db. Metadata only: title, link, author, date, <=300-char excerpt.
-- =====================================================================
CREATE TABLE feed_items (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id     uuid          NOT NULL,
    source_name   varchar(120)  NOT NULL,
    url           varchar(2000) NOT NULL,
    url_hash      char(64)      NOT NULL UNIQUE,            -- idempotency key for upserts
    title         varchar(300)  NOT NULL,
    excerpt       varchar(300)  CHECK (excerpt IS NULL OR char_length(excerpt) <= 300),
    author        varchar(120),
    section       varchar(20)   NOT NULL CHECK (section IN ('TECH', 'FOOTBALL', 'AI')),
    image_url     varchar(2000),
    ai_summary    varchar(400),                            -- Phase 8b (Curator agent)
    published_at  timestamptz   NOT NULL,
    ingested_at   timestamptz   NOT NULL DEFAULT now()
);
-- Same keyset-pagination indexes as posts (Phase 1).
CREATE INDEX feed_items_section_idx ON feed_items (section, published_at DESC, id DESC);
CREATE INDEX feed_items_recent_idx  ON feed_items (published_at DESC, id DESC);
