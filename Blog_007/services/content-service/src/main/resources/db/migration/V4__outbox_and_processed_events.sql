-- =====================================================================
-- V4 (Phase 6): the TRANSACTIONAL OUTBOX and consumer idempotency.
--
-- The "dual write" problem: publishing a post must (1) update Postgres and
-- (2) tell Kafka. Two separate systems can't share one transaction, so a crash
-- between the two steps loses the event (or sends an event for a rolled-back change).
-- Fix: write the event as a ROW in the SAME database transaction as the change.
-- A relay process later copies unpublished rows to Kafka (at-least-once).
-- =====================================================================
CREATE TABLE outbox (
    id            uuid         PRIMARY KEY,                 -- = the event id (EventEnvelope.id)
    aggregate_id  uuid         NOT NULL,                    -- which post (also the Kafka key)
    topic         varchar(200) NOT NULL,
    event_type    varchar(100) NOT NULL,
    payload       jsonb        NOT NULL,                    -- the full EventEnvelope as JSON
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz                               -- NULL = not yet sent to Kafka
);
-- The relay only ever looks for unpublished rows, oldest first: a small partial index.
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;

-- Consumers record which event ids they already handled (per consumer group), so a redelivered
-- message is recognised and skipped: "idempotent consumer".
CREATE TABLE processed_events (
    event_id        uuid         NOT NULL,
    consumer_group  varchar(100) NOT NULL,
    processed_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)
);
