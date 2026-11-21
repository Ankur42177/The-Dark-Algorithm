# 0017. Local Kubernetes with kind; expose services with NodePorts, not an Ingress controller
Date: 2026-11-21 · Status: Accepted

## Context
The autoscaling labs (PLAN §16.3) need a multi-node cluster on a 16 GB Mac (Docker: 8 GB shared with
Compose and Testcontainers). Browser and k6 must reach web, gateway and content-service.

## Options considered
1. **kind** — nodes are Docker containers; multi-node; conformant; fast create/delete. Images loaded with `kind load`.
2. **minikube** — VM or Docker driver; many addons; heavier, single node by default.
3. **k3d** (k3s) — lightest; bundles Traefik ingress; k3s differs slightly from upstream.
4. Docker Desktop Kubernetes — single node, tied to Docker Desktop.

Exposure:
a. **ingress-nginx** — the classic Ingress controller, retired upstream.
b. **Gateway API** implementation (Envoy Gateway, etc.) — the modern standard, but extra CRDs/pods for a laptop.
c. **NodePort + kind `extraPortMappings`** — zero extra components.

## Decision
kind 0.33.0 with `kindest/node:v1.35.8` (within kubectl 1.34's ±1 skew), 1 control-plane + 1 worker,
NodePorts 30000 (web), 30080 (api-gateway), 30081 (content-service) mapped to 127.0.0.1 on the Mac.
Our api-gateway (Phase 5) is the single front door, so no Ingress controller locally.

## Consequences
- Good: one-command up/down (`scripts/k8s-up.sh`, `k8s-down.sh`); nothing exposed beyond localhost.
- Bad: NodePorts aren't how production traffic enters; port mappings are fixed at cluster creation.
- Follow-up: Gateway API in the deployed environment (Phase 11).
