# 0001. Project decisions (PLAN.md §3)
Date: 2026-11-21 · Status: Accepted (defaults — change any time by superseding this ADR)

## Context
PLAN.md §3 lists six open decisions to settle before building. The owner asked to start
implementing straight away, so sensible defaults were chosen. Each can be changed later; the
cost of changing is noted.

## Options considered and decision
| # | Question | Decision | Cost to change later |
|---|---|---|---|
| 1 | New repo or evolve `footballhub`? | **New code base in `Blog_007/`** (git repo at `Microservices_Scale/`, local only — no GitHub remote yet) | Low: add a remote and push |
| 2 | Project name | **Keep "Pulse"** (`com.pulse` packages) | Medium: package rename |
| 3 | Build tool | **Maven multi-module** + Maven Wrapper (most common in Spring jobs; already installed) | High after several services exist |
| 4 | LLM | **Local Ollama only** for now; Claude via API is an optional prod profile in Phase 8/11 | Low: config only |
| 5 | Deployment target | **Stay local** until Phase 11 | None |
| 6 | Domain | **None yet** | None |

Layout deviation from PLAN.md §18: learning material lives in `../Learning_project/`
instead of `docs/learning/`, as the owner requested.

## Consequences
- Good: no external accounts or costs needed to finish Phases 0–10.
- Bad: no CI on GitHub Actions until a remote exists (Lighthouse CI in Phase 2 needs it).
- Follow-up: create the GitHub repo when the owner wants it (`gh` is not installed yet).
