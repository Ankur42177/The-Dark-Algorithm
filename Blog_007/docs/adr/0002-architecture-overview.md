# 0002. Architecture overview: microservices on purpose, to learn
Date: 2026-11-21 · Status: Accepted

## Context
Pulse is a single-author blog with an AI chatbot and curated external feeds. Real traffic would be
small (PLAN.md §16.1: ~46 req/s at a 10x peak, mostly absorbed by a CDN).

## Options considered
1. **Monolith** — one Spring Boot app, one DB. Pros: simplest, cheapest, no network hops, strong
   consistency. Cons: teaches none of the distributed-systems patterns the owner wants to learn.
2. **Modular monolith** — one deployable, strict internal modules. Pros: the right answer for this
   traffic in a real company; easy to split later. Cons: still no brokers, no independent scaling.
3. **Microservices** — gateway + content + ingestion + assistant services, Kafka, RabbitMQ, Redis,
   database per service. Pros: practises independent scaling, event-driven design, failure
   isolation, autoscaling. Cons: network latency, eventual consistency, much more ops work.

## Decision
Option 3, **explicitly as a learning choice**. Every place where it costs us something
(extra hops, eventual consistency, ops overhead) is called out in Learning_project/.

## Consequences
- Each service is stateless and owns its DB (one Postgres server, three databases, three users).
- Synthetic load (k6, 5,000 fake feeds) is needed to make scaling visible (Phase 10).
- Interview angle: "When would you NOT choose microservices?" → exactly this traffic profile.
