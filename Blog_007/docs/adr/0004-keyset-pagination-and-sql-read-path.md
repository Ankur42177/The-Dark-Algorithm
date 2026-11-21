# 0004. Keyset pagination with a hand-written SQL read path
Date: 2026-11-21 · Status: Accepted

## Context
Post lists (home, section pages, "load more") are the hottest queries. They must stay fast as the
table grows and while new posts are being published.

## Options considered
1. **OFFSET/LIMIT via Spring Data `Pageable`** — easiest; gives page numbers and total counts.
   Cons: cost grows with depth (DB reads and discards OFFSET rows); rows shift when new posts are
   inserted, so readers see duplicates or miss posts; COUNT(*) on every request.
2. **Keyset via Spring Data `ScrollPosition`/`Window`** — built-in keyset support. Cons: hides the
   SQL, which is the thing we want to learn.
3. **Keyset with explicit SQL through `JdbcClient`** + JPA only for single-entity loads/writes.

## Decision
Option 3. Cursor = Base64url("publishedAt|id"); query uses `(published_at, id) < (?, ?)` ordered by
`published_at DESC, id DESC` with partial indexes `WHERE status = 'PUBLISHED'`.

Measured on 100,000 rows (Postgres 18, warm cache, page ~3,000 of TECH):
| Query | Time | Buffers read |
|---|---|---|
| OFFSET 30000 LIMIT 10 | 11.4 ms (seq scan + sort of 30,010 rows) | 3,853 |
| Keyset, with index | 0.038 ms (index scan, 10 rows) | 5 |
| Keyset, index dropped | 5.7 ms (parallel seq scan) | 3,879 |

## Consequences
- Good: constant-time pages at any depth; stable under concurrent inserts; no COUNT(*).
- Bad: no "jump to page 57"; only next-page navigation (fine for infinite scroll / "load more").
- Follow-up: Phase 3 caches the first page per section in Redis.
