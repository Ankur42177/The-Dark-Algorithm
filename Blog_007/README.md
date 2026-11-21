# Pulse — code repository (Blog_007)

A learning-first microservices blog (Tech · Football · AI) with an AI chatbot.
Master plan: [`../PLAN.md`](../PLAN.md) · Learning notes: [`../Learning_project/`](../Learning_project/README.md)

## Status

| Phase | What | State |
|---|---|---|
| 0 | Foundations: layout, Docker Compose `core`, ADRs, developer tooling | ✅ done |
| 1 | content-service: posts on Postgres, keyset pagination, Problem Details, OpenAPI, tests | ✅ done |
| 2 | Next.js public website: ISR + tag revalidation + warming, Lighthouse 100 | ✅ done |
| 3 | Redis: cache-aside, single-flight, fail-open, views write-behind, trending | ✅ done |
| 4–11 | admin auth, gateway, Kafka, RabbitMQ ingestion, AI assistant, observability, Kubernetes, ship | in progress |

## Run it (from this folder)

```bash
cp .env.example .env            # first time only; then edit the passwords
docker compose -f infra/docker-compose.yml --env-file .env --profile core up -d   # Postgres + Redis
./mvnw -pl services/content-service spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open:

| URL | What you see |
|---|---|
| http://localhost:8081/api/v1/sections | Sections with post counts |
| http://localhost:8081/api/v1/posts?limit=3 | First page + `nextCursor` |
| http://localhost:8081/api/v1/posts/rag-in-one-picture | One full post |
| http://localhost:8081/swagger-ui.html | Interactive API docs |
| http://localhost:9081/actuator/health | Health (separate management port) |

## Test it

```bash
./mvnw -pl services/content-service verify   # unit tests + Testcontainers integration tests (Docker must be running)
```

## Layout

```text
Blog_007/
├── pom.xml                      parent POM (Maven multi-module)
├── mvnw, .mvn/                  Maven Wrapper (pinned Maven 3.9.16)
├── .env.example                 template for the git-ignored .env
├── docs/adr/                    architecture decision records
├── services/
│   ├── content-service/         Phase 1 ✅
│   ├── api-gateway/             Phase 5
│   ├── ingestion-service/       Phase 7
│   └── assistant-service/       Phase 8
├── libs/events/                 Phase 6
├── web/                         Phase 2
├── infra/
│   ├── docker-compose.yml       profiles: core (now), msg, ai, obs, apps (later)
│   ├── postgres/init/           creates content_db, ingestion_db, assistant_db + users
│   ├── k8s/, observability/     Phase 9–10
├── load-tests/k6/
└── scripts/                     load-100k-posts.sql (Phase 1 break-it)
```

---

**Designed and developed by Ankur Kumar.** © 2026 Ravenclaw_007 LLC. All rights reserved.
