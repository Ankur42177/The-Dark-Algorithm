# assistant-service — Harvey, Pulse's AI guide (Phase 8)

Harvey answers questions about Pulse's two arenas — **Code & Cortex** (`TECH`, `/tech`) and **Football** (`/football`) —
from Ankur's posts and the official blogs Pulse follows, with numbered citations. He can open pages for the reader and
keeps working ("search mode") when the language model is down or slow.

| Role (Spring profile) | What it does |
|---|---|
| `api` (default) | `POST /api/v1/assistant/chat` → Server-Sent Events (contract below) |
| `embedder` | Keeps the vector index in sync: Kafka group `assistant-embedder` (`content.post.events`, `ingestion.article.events`) **plus** a reconciler at startup and every 30 min |

## How an answer is made

1. **Front desk** (`IntentRouter`, no LLM): "take me to football" → instant navigation; "hi / who are you" → persona only.
2. **Retrieval first**: everything else gets sources fetched *before* the model runs — a publisher named
   ("What's new from LangChain?") → its newest articles; "new / latest / hot" → newest posts + articles; otherwise
   hybrid search (pgvector + Postgres full-text, fused with RRF). Sources are numbered `[1]…[n]`.
3. **Agent loop** (`AgentService`): the model (Ollama `qwen3:4b-instruct`) answers and cites `[n]`; it may still call
   tools (`getLatestPosts`, `searchContent`, `getPost`, `listSections`, `navigate`), max 4 per question.
4. **Citations are checked**: a `[n]` only becomes a card if its sentence shares distinctive words with source n.
   Refusals get no cards and are never cached.

Guardrails: untrusted-data fences + persona rule against prompt injection, navigation allow-list
(`/`, `/tech`, `/football`, `/contact`, `/#newsletter`, `/posts/<slug>`), 512 output tokens, 20 s budget,
`num_ctx` 8192, circuit breaker → search mode, Stop cancels generation, Redis memory (10 messages, 24 h) and a
10-minute answer cache for grounded first questions.

## SSE contract (additive changes only)

```
event: status    {"stage":"searching|thinking|tool|fallback","text":"Searching Pulse…"}
event: token     {"text":"…"}                       text may contain [n] = citation.ref
event: citation  {"ref":1,"title","url","slug","source","section":"TECH|FOOTBALL","type":"POST|ARTICLE","imageUrl","external"}
event: action    {"type":"NAVIGATE","path":"/football","auto":true,"label":"Football"}
event: done      {"usage":{"mode":"llm|search|cache|nav","toolCalls","tools","promptTokens","completionTokens","ttftMs","totalMs","sessionId","reason"}}
event: error     {"message":"…"}                    terminal: no done follows
```

## Run locally

```bash
ollama pull qwen3:4b-instruct && ollama pull nomic-embed-text
cd Blog_007/services/assistant-service            # it reads ../../.env
java -jar target/assistant-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=api,embedder
curl -s localhost:8083/internal/v1/embed/stats                  # index health per type/section/source + last reconcile
curl -s -X POST localhost:8083/internal/v1/embed/backfill       # reconcile now (?force=true re-embeds everything)
```
Behind the api-gateway, CORS is the gateway's job; set `PULSE_ASSISTANT_CORS_ORIGINS` only for direct browser calls.

## Test

```bash
./mvnw -pl services/assistant-service verify          # unit + Testcontainers ITs with a fake LLM (stop the running jar first!)
./mvnw -pl services/assistant-service -Pevals verify  # golden set vs the real local model + a snapshot of real content
```
The golden set (`src/test/resources/evals/golden.yaml`) runs against `content-snapshot.json` (all posts + the newest
articles per feed). It reports answer quality, citation hit rate, fallback rate and TTFT/total p50/p95.

Decisions: `docs/adr/0014-ai-agent-loop-and-guardrails.md`, `docs/adr/0015-rag-hybrid-search-pgvector.md`.

---
Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
