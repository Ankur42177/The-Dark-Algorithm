# 0015. RAG with our own pgvector table, heading-aware chunking and Reciprocal Rank Fusion
Date: 2026-11-21 · Status: Accepted

## Context
The agent's searchContent tool must find posts by meaning ("how do readers share work in Kafka?") and by
exact terms ("PPDA", "Arsenal"). assistant_db has pgvector (PLAN §11.3).

## Options considered
1. **Spring AI PgVectorStore** — ready-made table/queries. Cons: its own schema; harder to show and tune SQL.
2. **Own `doc_chunks` table via JdbcClient** — explicit SQL (`embedding <=> query` with an HNSW index),
   UPSERT by (doc_type, doc_id, chunk_index), best-chunk-per-document dedupe.
3. Vector-only or keyword-only search — each misses what the other finds.

## Decision
Option 2 + hybrid: pgvector cosine similarity fused with content-service full-text results using RRF
(k = 60, rank-based, so incomparable scores don't matter). Chunking: split rendered HTML at h1–h3, merge
small sections, ~2,000 chars (~500 tokens) per chunk with 200-char overlap; nomic task prefixes
`search_document:` / `search_query:`. Articles: one chunk (title + ≤300-char excerpt, fair-use rules).
Embedder role consumes `content.post.events` + `ingestion.article.events` (group assistant-embedder),
idempotent via processed_events; `POST /internal/v1/embed/backfill` rebuilds the index.

## Consequences
- Good: works without Kafka (backfill); semantic half degrades to keyword search when Ollama is down.
- Bad: re-embedding all posts is needed when the embedding model changes (vectors aren't comparable).
