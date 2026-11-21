-- =====================================================================
-- V7: newsletter (monthly random article, double opt-in) + contact form.
--
-- Lives in content-service because the newsletter mails OUR posts and
-- "each service owns its data": nobody else may read the posts table.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Subscribers. Life cycle:  PENDING --confirm--> ACTIVE --unsubscribe--> UNSUBSCRIBED
--                               ^                                            |
--                               +-------------- subscribe again -------------+
--
-- TOKENS ARE NEVER STORED. The email contains a random 32-byte token; we keep
-- only its SHA-256 hash (64 hex chars). If the database leaks, the attacker
-- gets hashes, which cannot be turned back into working links. (Same idea as
-- password hashing; a fast hash is fine here because the token is 256 random
-- bits, not a guessable password.)
-- ---------------------------------------------------------------------
CREATE TABLE newsletter_subscribers (
    id                      uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- RFC 5321 caps an address at 254 chars. Stored lower-case so "Ankur@X.com" and
    -- "ankur@x.com" are ONE subscriber; UNIQUE makes a racing double-subscribe safe.
    email                   varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    name                    varchar(100),
    status                  varchar(20)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'UNSUBSCRIBED')),
    confirm_token_hash      char(64)     UNIQUE,         -- NULL once unsubscribed
    confirm_expires_at      timestamptz,                 -- confirm link valid for 48 h
    confirm_sent_at         timestamptz,                 -- anti "email bombing": max one confirm mail per cooldown
    unsubscribe_token_hash  char(64)     UNIQUE,         -- created on confirm; never expires (link in every email)
    created_at              timestamptz  NOT NULL DEFAULT now(),
    confirmed_at            timestamptz,
    unsubscribed_at         timestamptz
);
-- The monthly job walks ACTIVE subscribers in id order (keyset batches). Partial index: only ACTIVE rows.
CREATE INDEX newsletter_subscribers_active_idx ON newsletter_subscribers (id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- One row per (subscriber, month) that was mailed. The UNIQUE constraint is the
-- whole idempotency story: two replicas running the monthly job at the same time
-- both try to INSERT; Postgres lets exactly one win, the other gets "0 rows".
-- ---------------------------------------------------------------------
CREATE TABLE newsletter_deliveries (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscriber_id  uuid        NOT NULL REFERENCES newsletter_subscribers (id) ON DELETE CASCADE,
    period         char(7)     NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),   -- 'YYYY-MM'
    -- SET NULL: the admin may delete a post later; the delivery history must not block that.
    post_id        uuid        REFERENCES posts (id) ON DELETE SET NULL,
    claimed_at     timestamptz NOT NULL DEFAULT now(),  -- row inserted = "this month's mail is mine to send"
    sent_at        timestamptz,                         -- NULL = claimed but the send never finished (crash)
    CONSTRAINT newsletter_one_mail_per_month UNIQUE (subscriber_id, period)
);
-- "Which posts did this subscriber already get?" (the random pick excludes them).
CREATE INDEX newsletter_deliveries_post_idx ON newsletter_deliveries (subscriber_id, post_id);

-- ---------------------------------------------------------------------
-- Contact form messages. Lengths are enforced here AND in Java (defence in depth).
-- ip_hash: HMAC-SHA256 of the sender IP with a server secret — lets the admin spot
-- "50 messages from the same sender" without storing the IP itself (privacy).
-- ---------------------------------------------------------------------
CREATE TABLE contact_messages (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(100)  NOT NULL,
    email       varchar(254)  NOT NULL,
    message     varchar(5000) NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    handled_at  timestamptz,
    ip_hash     char(64)
);
CREATE INDEX contact_messages_recent_idx ON contact_messages (created_at DESC);
