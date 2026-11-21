# assistant-service — "Ask Pulse" (Phase 8)

An AI agent that answers questions about Pulse content with citations, can open pages for the reader, and
keeps working (in "search mode") when the language model is down.

| Role (Spring profile) | What it does |
|---|---|
| `api` (default) | `POST /api/v1/assistant/chat` → Server-Sent Events: `token`, `citation`, `action`, `done`, `error` |
| `embedder` | Kafka group `assistant-embedder` on `content.post.events` + `ingestion.article.events` → chunks → embeddings → pgvector |

Tools the model may call: `getLatestPosts`, `searchContent` (pgvector + full-text, fused with RRF), `getPost`,
`listSections`, `navigate` (allow-listed internal paths only). Guardrails: max 4 tool calls, 512 output
tokens, 20 s budget, untrusted-data fences, circuit breaker → search mode, Redis memory + answer cache.

## Run locally

```bash
brew install ollama && brew services start ollama
ollama pull qwen3:4b && ollama pull nomic-embed-text
cd Blog_007
./mvnw -f services/assistant-service/pom.xml spring-boot:run              # api role on :8083
curl -X POST localhost:8083/internal/v1/embed/backfill                     # embed all published posts
curl -N -H 'Content-Type: application/json' -d '{"message":"What is new in AI this week?"}' \
     localhost:8083/api/v1/assistant/chat
```
With Kafka running: `--spring.profiles.active=api,embedder`.

## Test

```bash
./mvnw -f services/assistant-service/pom.xml verify          # unit + Testcontainers ITs with a fake LLM
./mvnw -f services/assistant-service/pom.xml -Pevals verify  # golden-set evals against the real local model
```

Decisions: `docs/adr/0014-ai-agent-loop-and-guardrails.md`, `docs/adr/0015-rag-hybrid-search-pgvector.md`.

---
Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
