# api-gateway (Phase 5)

The single front door for Pulse's API: **Spring Cloud Gateway (WebFlux)** on port **8080**
(actuator on **9080**). Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC.

## What it does
| Concern | How | Code |
|---|---|---|
| Routing | `/api/v1/posts/**`, `/sections`, `/trending`, `/search/**`, `/feed/**`, `/auth/**`, `/admin/posts/**`, `/admin/preview`, `/.well-known/jwks.json` → content-service; `/admin/sources/**`, `/admin/ingestion/**` → ingestion-service; `/assistant/**` → assistant-service | `GatewayRoutes` |
| JWT at the edge | `/api/v1/admin/**` needs a valid RS256 JWT with role ADMIN (header or `pulse_at` cookie), verified with content-service's JWKS | `SecurityConfig` |
| Rate limiting | Redis token buckets per client IP: public 60/min, login 5/15 min, chat 10/min + 100/day, admin 300/min → 429 Problem Details + Retry-After | `RateLimitPolicy`, `RateLimiting` |
| Correlation IDs | `X-Request-Id` kept or generated, forwarded, echoed, logged (`pulse.gateway.access`) | `RequestIdFilter` |
| Spoofing protection | strips incoming `X-Forwarded-*` at the edge, then adds the real client IP | `RequestIdFilter`, `trusted-proxies` |
| CORS | web origin with credentials; duplicate CORS headers from services removed | `SecurityConfig`, `GatewayRoutes.common()` |
| Limits & timeouts | 2 MB bodies (413), connect 1 s, response 5 s (assistant 60 s), GET-only retries with backoff + jitter | `GatewayRoutes`, `application.yml` |
| Clean failures | downstream down → **503**, too slow → **504**, all as Problem Details with `requestId` | `ProblemErrorHandler` |

## Run
```bash
cd Blog_007
docker compose -f infra/docker-compose.yml --env-file .env --profile core up -d     # Redis
./mvnw -pl services/content-service spring-boot:run -Dspring-boot.run.profiles=dev # downstream
./mvnw -pl services/api-gateway spring-boot:run
curl -i localhost:8080/api/v1/sections               # X-Request-Id, X-RateLimit-Remaining
curl -s localhost:9080/actuator/gateway/routes | jq  # the routing table
k6 run load-tests/k6/phase5-rate-limit.js            # 60 × 200 then 429s
```
Config (env): `CONTENT_SERVICE_URI`, `INGESTION_SERVICE_URI`, `ASSISTANT_SERVICE_URI`, `REDIS_HOST`,
`PULSE_CORS_ORIGINS`, `PULSE_JWT_ISSUER`, `PULSE_TRUSTED_PROXY_HOPS`, `PULSE_TRUSTED_PROXIES`,
`PULSE_RATE_LIMIT_MULTIPLIER` (load tests only).

## Test
`./mvnw -pl services/api-gateway verify` — `GatewayIT` (12 tests): real gateway + Testcontainers Redis + a JDK
HttpServer stub downstream serving a test JWKS.

Decisions: `docs/adr/0008-api-gateway-spring-cloud-gateway-webflux.md`, `docs/adr/0009-edge-rate-limiting-and-client-identity.md`.
