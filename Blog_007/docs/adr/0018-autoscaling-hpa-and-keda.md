# 0018. Autoscaling: HPA on CPU for request-serving services, KEDA for queue/stream consumers
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §16.2/16.3: request-serving services scale with traffic; background workers should scale with
work waiting and to zero when idle; Kafka consumers are capped by partition count.

## Options considered
1. **HPA on CPU everywhere** — simple, built in. Workers would sit at minReplicas while a queue grows,
   because waiting messages don't use CPU; no scale-to-zero.
2. **HPA with custom metrics (Prometheus Adapter)** — flexible, but adapter config is complex.
3. **KEDA for event-driven workloads + plain HPA for HTTP services** — KEDA scalers read RabbitMQ queue
   length, Kafka consumer lag, Redis list length, cron…, and drive an HPA it manages; supports 0 replicas.

## Decision
Option 3. HPA (autoscaling/v2, CPU 60 % of request, scale-up window 0 s, scale-down window 300 s in prod /
60 s on kind) for content-service, api-gateway, assistant-api, web. KEDA 2.21.1 ScaledObjects for
ingestion-worker (`rabbitmq` QueueLength 20 on `feed.fetch.q`, 0–10) and assistant-embedder (`kafka`
lag 50 on `content.post.events`, 1–6 = partition count). A Deployment never has both (chart `fail`s).
On kind, a `queue-demo-worker` demonstrates KEDA with a `redis` list trigger until RabbitMQ/Kafka workers exist.
metrics-server 0.9.0 (chart 3.14.0) provides CPU metrics (`--kubelet-insecure-tls` on kind only).

## Consequences
- Good: workers cost nothing when idle; scaling reacts to backlog, not symptoms.
- Bad: KEDA is another control-plane component to run and upgrade; scale-from-zero adds JVM startup latency to the first job.
- Follow-up: measured numbers in Learning notes; cluster autoscaler for nodes in a real cloud.
