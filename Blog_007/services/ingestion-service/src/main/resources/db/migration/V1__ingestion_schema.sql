-- =====================================================================
-- ingestion_db (owned ONLY by ingestion-service).
-- =====================================================================
CREATE TABLE feed_sources (
    id                    uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  varchar(120) NOT NULL,
    site_url              varchar(500) NOT NULL,
    feed_url              varchar(500) NOT NULL UNIQUE,
    section               varchar(20)  NOT NULL CHECK (section IN ('TECH', 'FOOTBALL', 'AI')),
    fetch_interval_min    integer      NOT NULL DEFAULT 30 CHECK (fetch_interval_min BETWEEN 15 AND 1440), -- polite: >= 15 min
    etag                  varchar(500),          -- conditional GET: If-None-Match
    last_modified         varchar(100),          -- conditional GET: If-Modified-Since
    last_fetched_at       timestamptz,
    next_fetch_at         timestamptz  NOT NULL DEFAULT now(),
    consecutive_failures  integer      NOT NULL DEFAULT 0,
    active                boolean      NOT NULL DEFAULT true,
    disabled_reason       varchar(300),
    created_at            timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX feed_sources_due_idx ON feed_sources (next_fetch_at) WHERE active;

-- One row per fetch attempt: the admin "ingestion runs" page and our debugging trail.
CREATE TABLE fetch_runs (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id    uuid         NOT NULL REFERENCES feed_sources (id) ON DELETE CASCADE,
    message_id   uuid,
    attempt      integer      NOT NULL DEFAULT 1,
    started_at   timestamptz  NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    status       varchar(20)  NOT NULL CHECK (status IN ('RUNNING', 'OK', 'NOT_MODIFIED', 'FAILED', 'SKIPPED')),
    http_status  integer,
    items_seen   integer      NOT NULL DEFAULT 0,
    items_new    integer      NOT NULL DEFAULT 0,
    error        varchar(500)
);
CREATE INDEX fetch_runs_recent_idx ON fetch_runs (started_at DESC);

-- Every distinct article we've seen. url_hash UNIQUE = the durable dedupe key (Redis is the fast one).
-- payload = the complete Kafka event (EventEnvelope JSON); published_at NULL = not yet on Kafka (a mini outbox).
CREATE TABLE raw_items (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id     uuid          NOT NULL REFERENCES feed_sources (id) ON DELETE CASCADE,
    guid          varchar(1000),
    url           varchar(2000) NOT NULL,
    url_hash      char(64)      NOT NULL UNIQUE,
    payload       jsonb         NOT NULL,
    fetched_at    timestamptz   NOT NULL DEFAULT now(),
    published_at  timestamptz
);
CREATE INDEX raw_items_unpublished_idx ON raw_items (source_id) WHERE published_at IS NULL;

-- Messages that failed for good (after 5 attempts, or a permanent error). Shown in the admin DLQ page.
CREATE TABLE dead_letters (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id   uuid          NOT NULL,
    source_id    uuid,
    attempts     integer       NOT NULL,
    reason       varchar(1000) NOT NULL,
    payload      jsonb         NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    requeued_at  timestamptz
);

-- ShedLock's table: one row per named job; a replica "owns" the job until lock_until.
CREATE TABLE shedlock (
    name        varchar(64)  PRIMARY KEY,
    lock_until  timestamp    NOT NULL,
    locked_at   timestamp    NOT NULL,
    locked_by   varchar(255) NOT NULL
);
