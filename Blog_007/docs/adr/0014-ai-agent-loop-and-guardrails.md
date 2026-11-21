# 0014. Hand-written agent loop with explicit guardrails, local Ollama model
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §13: an agent that calls tools, streams answers (SSE), cites sources, can navigate the site, resists
prompt injection from external feed text, and degrades instead of failing. It must run free and offline in
development on a 16 GB Mac that also runs Docker.

## Options considered
1. **ChatClient with framework tool execution** (Spring AI's ToolCallingAdvisor) — least code. Cons: the
   tool loop is hidden; per-question tool budget, untrusted-data fencing and breaker accounting need hooks.
2. **Own loop over `ChatModel.stream()`** — the model returns tool calls; our code runs them (max 4), fences
   results as untrusted data, offers no tools once the budget is spent, enforces a 20 s deadline and drives a
   Resilience4j circuit breaker. A fake `ChatModel` makes every guardrail unit-testable.
3. Hosted LLM by default — better quality, but costs money and needs a key; kept as an optional prod profile.

## Decision
Option 2 with Ollama locally: chat model **qwen3:4b-instruct** (2.5 GB, tool calling, NON-thinking edition),
embeddings **nomic-embed-text** (274 MB, 768 dims). Guardrails: navigation allow-list enforced in code,
`<tool_result trust="untrusted">` fences (with a raw-text result converter so the fence isn't JSON-escaped),
breaker → "search mode", Redis chat memory (10 messages, 24 h), answer cache (10 min, first questions only).

## Model selection finding
The plain `qwen3:4b` tag is now the *thinking* edition. Measured on this Mac: with Ollama `think:false` it still
wrote its reasoning into the answer text (one-sentence question: 11.8 s, 317 tokens); the `/no_think` prompt switch
made it worse (55 s, 1,136 tokens); through the agent a "what's new" question exceeded the 20 s budget.
The `qwen3:4b-instruct` edition (same size) has no reasoning phase and is the right choice for a chat widget.

## Eval design
Golden-set runs reset the circuit breaker before each case, warm the models up before timing, and report
MODEL QUALITY (llm-mode cases) separately from RESILIENCE (fallback answers count as INFRA, not FAIL). A first
run on an overloaded laptop scored 2/20 because the breaker opened and the eval measured search mode instead.

## Consequences
- Good: every guardrail is visible, tested (AssistantIT) and measurable (golden-set evals, `done` usage event).
- Bad: more code than option 1; small local models pick tools less reliably than large hosted ones.
- Follow-up: run the golden set on every model change; consider a hosted model profile for production.
