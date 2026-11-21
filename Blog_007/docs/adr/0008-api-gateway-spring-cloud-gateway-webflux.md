# 0008. API gateway: Spring Cloud Gateway on WebFlux, Spring Cloud 2025.1.5 on Boot 4.1.3
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §7.1 / Phase 5: one front door for all API traffic — routing, JWT at the edge, distributed rate
limiting, correlation IDs, CORS, timeouts, clean errors. Project baseline: Spring Boot 4.1.3 (ADR-0003).

Version check (Maven Central, 2026-11-21):
- Spring Cloud **2025.1.5** (gateway 5.0.5) is *built* on Boot 4.0.10, but its compatibility verifier
  (`spring.cloud.compatibility-verifier.compatible-boot-versions`) accepts **4.0.x and 4.1.x** → supported on 4.1.3.
- Spring Cloud **2026.0.0** is built on Boot **4.2.0** → would force this module onto a different Boot minor.

## Options considered
1. **Spring Cloud Gateway — WebFlux (`spring-cloud-starter-gateway-server-webflux`)**
   + built-in Redis token-bucket `RedisRateLimiter` (atomic Lua script), mature filter set (Retry, RequestSize,
     DedupeResponseHeader), Netty event loop handles many concurrent/long-lived (SSE) connections cheaply.
   − reactive programming model (Mono/Flux) is new for the team; Spring Security config differs from MVC.
2. **Spring Cloud Gateway — Server WebMVC (`…-server-webmvc`)**
   + same blocking style as content-service, works great with virtual threads.
   − rate limiting via Bucket4j integration (extra dependency + its own Redis/JCache setup); fewer built-ins.
3. **Off-the-shelf proxy (NGINX/Envoy/Kong) in front of services**
   + very fast, battle-tested. − JWT/JWKS, Redis limits and Problem Details need plugins/Lua; less to learn about Spring.

## Decision
Option 1 with Spring Cloud **2025.1.5** on Boot **4.1.3** (no Boot override). Spring Cloud BOM imported only
in the api-gateway module. Routes are defined in Java (`GatewayRoutes`), so per-route filters are explicit.

## Consequences
- Good: distributed rate limiting with no extra library; edge JWT validation via JWKS; 503/504 Problem Details.
- Bad: two programming models in the codebase (MVC services, reactive gateway).
- Gotchas found while building (both covered by tests):
  - YAML `default-filters` do NOT apply to Java-DSL routes → common filters added in `GatewayRoutes.common()`.
  - Gateway 5 only adds `X-Forwarded-*` when `spring.cloud.gateway.server.webflux.trusted-proxies` is set.
- Follow-up: move to Spring Cloud 2026.0 together with the Boot 4.2 upgrade of all services.
