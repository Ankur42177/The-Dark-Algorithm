-- =====================================================================
-- V2 (Phase 3): view counts per post per day.
-- Views are counted in Redis (INCR, microseconds) and flushed here once a
-- minute in batches: "write-behind". 400k page views/day become ~1.4k writes.
-- =====================================================================
CREATE TABLE post_daily_views (
    post_id  uuid    NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    day      date    NOT NULL,
    views    bigint  NOT NULL DEFAULT 0 CHECK (views >= 0),
    PRIMARY KEY (post_id, day)      -- one row per post per day; the flush job UPSERTs into it
);
