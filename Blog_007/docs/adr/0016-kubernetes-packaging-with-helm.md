# 0016. Package Pulse for Kubernetes with one generic Helm chart
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §20 Phase 10 needs every service (content, gateway, ingestion scheduler/worker, assistant api/embedder,
web) on Kubernetes with probes, resources, autoscaling, PDBs and graceful shutdown, deployable to a local
kind cluster and later to a real cluster.

## Options considered
1. **Plain YAML per service** — explicit, but 7 near-identical Deployments to keep in sync.
2. **Kustomize** (base + overlays, built into kubectl) — no templating language; patches per environment.
   Cons: loops/conditionals (enable a service, HPA-or-KEDA) are awkward; no release history/rollback.
3. **One Helm chart with generic templates that `range` over `services:` in values** — a new service or
   role is a values entry; per-environment values files; release revisions + `helm rollback`; `helm test`.
4. **Umbrella chart with a subchart per service** — more isolation, more boilerplate.

## Decision
Option 3: `infra/k8s/helm/pulse`, single chart, templates for Deployment/Service/ConfigMap/HPA/ScaledObject/
PDB/ServiceAccount/NetworkPolicy plus optional in-cluster Postgres/Redis/Kafka/RabbitMQ StatefulSets.
Secrets are created **outside Helm** (`scripts/k8s-up.sh` from `.env`) so they never enter values files or
Helm release history. Images: one parametrized multi-stage Dockerfile for Spring services (layered jar,
non-root uid 10001, `MaxRAMPercentage=75`) and a 3-stage Next.js standalone Dockerfile.
Render-time guards: `fail` if a service enables both HPA and KEDA; `required` for chart-managed secrets.

## Consequences
- Good: 21 objects for kind / 48 with everything enabled from ~10 templates; enabling Phase 5–8 services is a flag.
- Bad: Go templating whitespace errors; generic templates are harder to read than plain YAML (mitigated by `helm template` in reviews and the lessons).
- Follow-up: publish images to a registry and the chart to an OCI registry in Phase 11; External Secrets in production.
