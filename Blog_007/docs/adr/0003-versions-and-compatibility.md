# 0003. Pinned versions and compatibility
Date: 2026-11-21 · Status: Accepted

## Context
PLAN.md §17 says: use Spring Boot 4.1.x until Spring Cloud and Spring AI officially support 4.2,
and pin the newest stable patch versions at build time. Checked on Maven Central / Docker Hub today.

## Options considered
1. **Spring Boot 4.2.0 + Spring Cloud 2026.0.0 + Spring AI 2.1.0** — newest, but 4.2.0 is days-old
   GA and springdoc 3.2.x is the only line built on it. Higher risk of early bugs.
2. **Spring Boot 4.1.3 + Spring Cloud 2025.1.5 + Spring AI 2.0.3 + springdoc 3.1.1** — latest
   patches of the line the plan specifies; springdoc 3.1.1 is built against Boot 4.1.

## Decision
Option 2.

| Component | Version |
|---|---|
| Java | 25 (LTS) |
| Spring Boot | 4.1.3 (Spring Framework 7.0, Hibernate 7.4, Jackson 3.1, Flyway 12.4, Testcontainers 2.0.5) |
| Spring Cloud | 2025.1.5 (Phase 5) |
| Spring AI | 2.0.3 (Phase 8) |
| springdoc-openapi | 3.1.1 |
| Maven (wrapper) | 3.9.16 |
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.7-pg18` (Postgres 18.7) |
| Redis | `redis:8.8.4-alpine` |

## Consequences
- Revisit after Phase 5: move to Boot 4.2 + Cloud 2026.0 + AI 2.1 together, in one commit.
