-- =====================================================================
-- assistant_db V1: the vector index for RAG (Phase 8).
-- pgvector adds a `vector(N)` column type and distance operators:
--   <=> cosine distance (0 = same direction, 2 = opposite), <-> L2 distance, <#> negative dot product
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS vector;   -- already created by the init script; harmless if present

CREATE TABLE doc_chunks (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_type      varchar(10)  NOT NULL CHECK (doc_type IN ('POST', 'ARTICLE')),
    doc_id        varchar(200) NOT NULL,          -- post UUID, or article id/URL hash
    chunk_index   integer      NOT NULL,
    title         varchar(300) NOT NULL,
    url           varchar(600) NOT NULL,          -- /posts/{slug} for Ankur's posts, the original URL for articles
    section       varchar(20),
    source        varchar(120) NOT NULL,          -- "Pulse" or the publisher name
    content       text         NOT NULL,
    embedding     vector(768)  NOT NULL,          -- nomic-embed-text produces 768 numbers per text
    published_at  timestamptz,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (doc_type, doc_id, chunk_index)        -- re-embedding a post UPSERTs instead of duplicating
);

-- HNSW = "Hierarchical Navigable Small World": an approximate nearest-neighbour index.
-- Finds the closest vectors in ~log(N) hops instead of comparing against every row.
CREATE INDEX doc_chunks_embedding_hnsw ON doc_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX doc_chunks_doc_idx ON doc_chunks (doc_type, doc_id);

-- Consumer idempotency: an event id is processed at most once even if Kafka redelivers it.
CREATE TABLE processed_events (
    event_id      varchar(100) PRIMARY KEY,
    processed_at  timestamptz  NOT NULL DEFAULT now()
);

-- Thumbs up/down on answers (PLAN §11.3); used later to improve the agent.
CREATE TABLE chat_feedback (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  varchar(64)  NOT NULL,
    question    text         NOT NULL,
    answer      text         NOT NULL,
    rating      smallint     NOT NULL CHECK (rating IN (-1, 1)),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
