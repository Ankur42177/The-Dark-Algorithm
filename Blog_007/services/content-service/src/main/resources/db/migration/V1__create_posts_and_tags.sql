-- =====================================================================
-- V1: posts, tags and the post_tags join table.
--
-- Flyway runs files named V<version>__<description>.sql exactly ONCE, in
-- version order, and records each run in the flyway_schema_history table.
-- NEVER edit a migration after it has run anywhere: add V2, V3, ... instead.
-- (Flyway stores a checksum and refuses to start if an applied file changed.)
-- =====================================================================

CREATE TABLE posts (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug             varchar(160) NOT NULL UNIQUE,
    title            varchar(200) NOT NULL,
    summary          varchar(400),
    body_md          text         NOT NULL,
    body_html        text         NOT NULL,
    section          varchar(20)  NOT NULL CHECK (section IN ('TECH', 'FOOTBALL', 'AI')),
    status           varchar(20)  NOT NULL CHECK (status IN ('DRAFT', 'SCHEDULED', 'PUBLISHED', 'UNPUBLISHED')),
    cover_image_url  varchar(500),
    reading_minutes  integer      NOT NULL DEFAULT 1 CHECK (reading_minutes > 0),
    published_at     timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,

    -- Full-text search document, computed BY POSTGRES whenever the row changes.
    -- Weights: title matters most (A), then summary (B), then body (C).
    search_tsv       tsvector GENERATED ALWAYS AS (
                         setweight(to_tsvector('english', coalesce(title, '')),   'A') ||
                         setweight(to_tsvector('english', coalesce(summary, '')), 'B') ||
                         setweight(to_tsvector('english', coalesce(body_md, '')), 'C')
                     ) STORED,

    -- A published post must have a publish date (the DB enforces it, not just Java).
    CONSTRAINT posts_published_has_date CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- INDEXES. An index is a sorted copy of some columns + a pointer to the row,
-- like the index at the back of a book. Without one, Postgres reads EVERY row
-- ("sequential scan") to answer a query.
-- ---------------------------------------------------------------------

-- Section pages: "newest published posts in TECH". PARTIAL index (WHERE ...):
-- drafts are never in it, so it is smaller and faster. Column order matches
-- the query's WHERE section = ? ... ORDER BY published_at DESC, id DESC.
CREATE INDEX posts_published_by_section_idx
    ON posts (section, published_at DESC, id DESC)
    WHERE status = 'PUBLISHED';

-- Home page: "newest published posts in ANY section".
CREATE INDEX posts_published_idx
    ON posts (published_at DESC, id DESC)
    WHERE status = 'PUBLISHED';

-- GIN ("generalized inverted index") = word -> list of rows, like a search engine.
CREATE INDEX posts_search_idx ON posts USING GIN (search_tsv);

CREATE TABLE tags (
    id    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name  varchar(50) NOT NULL UNIQUE CHECK (name = lower(name))
);

-- Many-to-many link. The composite primary key stops duplicate (post, tag) pairs.
-- ON DELETE CASCADE: deleting a post deletes its links automatically.
CREATE TABLE post_tags (
    post_id  uuid   NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    tag_id   bigint NOT NULL REFERENCES tags (id)  ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

-- The PK above is (post_id, tag_id), which helps "tags of a post".
-- For "posts with a tag" we need the reverse direction too.
CREATE INDEX post_tags_tag_idx ON post_tags (tag_id, post_id);
