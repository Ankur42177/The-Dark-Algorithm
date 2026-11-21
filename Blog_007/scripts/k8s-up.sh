#!/usr/bin/env bash
# =====================================================================
# One command to a running Pulse on Kubernetes (kind):
#   ./scripts/k8s-up.sh                       content-service + web + Postgres + Redis + KEDA demo
#   PULSE_SERVICES="content-service api-gateway" ./scripts/k8s-up.sh    also build/enable the gateway
# Steps: cluster → jars → images → kind load → metrics-server + KEDA → Secret → helm upgrade --install → warm-up
# Undo everything: ./scripts/k8s-down.sh
# Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=pulse
NS=pulse
TAG=0.1.0
SERVICES=${PULSE_SERVICES:-content-service}
METRICS_SERVER_CHART=3.14.0     # app 0.9.0
KEDA_CHART=2.21.1
CHART=infra/k8s/helm/pulse

for tool in docker kind kubectl helm; do command -v $tool >/dev/null || { echo "missing $tool (brew install $tool)"; exit 1; }; done
[ -f .env ] || { echo ".env missing: cp .env.example .env && ./scripts/gen-jwt-keys.sh && ./scripts/hash-password.sh"; exit 1; }

echo "==> 1/7 cluster"
kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || kind create cluster --config infra/k8s/kind/cluster.yaml
kubectl config use-context "kind-$CLUSTER" >/dev/null

echo "==> 2/7 build jars + images"
EXTRA_SETS=()
for svc in $SERVICES; do
  [ -f "services/$svc/pom.xml" ] || { echo "   skip $svc (module not built yet)"; continue; }
  ./mvnw -q -pl "services/$svc" -am package -DskipTests
  JAR=$(ls services/$svc/target/$svc-*.jar | grep -v plain | head -1)
  docker build -q -f infra/docker/spring-service.Dockerfile --build-arg SERVICE=$svc --build-arg JAR_FILE=$JAR -t pulse/$svc:$TAG . >/dev/null
  kind load docker-image pulse/$svc:$TAG --name $CLUSTER
  [ "$svc" != "content-service" ] && EXTRA_SETS+=(--set "services.$svc.enabled=true")
done
WEB_API=http://localhost:30081
[[ " $SERVICES " == *" api-gateway "* ]] && WEB_API=http://localhost:30080
docker build -q --build-arg NEXT_PUBLIC_API_URL=$WEB_API -t pulse/web:$TAG web >/dev/null
kind load docker-image pulse/web:$TAG --name $CLUSTER

# Infra images you already pulled for Docker Compose: copy them into the nodes instead of downloading again.
for img in pgvector/pgvector:0.8.7-pg18 redis:8.8.4-alpine busybox:1.37.0; do
  docker image inspect "$img" >/dev/null 2>&1 && kind load docker-image "$img" --name $CLUSTER >/dev/null || true
done

echo "==> 3/7 metrics-server (CPU/memory metrics for HPA) + KEDA (event-driven autoscaling)"
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ >/dev/null 2>&1 || true
helm repo add kedacore https://kedacore.github.io/charts >/dev/null 2>&1 || true
helm repo update >/dev/null
# kind's kubelets use self-signed certs → metrics-server must skip verifying them (local clusters ONLY).
helm upgrade --install metrics-server metrics-server/metrics-server --version $METRICS_SERVER_CHART \
  -n kube-system --set 'args={--kubelet-insecure-tls}' --wait >/dev/null
helm upgrade --install keda kedacore/keda --version $KEDA_CHART -n keda --create-namespace --wait >/dev/null

echo "==> 4/7 namespace + Secret (created outside Helm, from .env)"
kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f - >/dev/null
val() { grep -E "^$1=" .env | head -1 | cut -d= -f2- | sed -E "s/^'(.*)'\$/\1/; s/^\"(.*)\"\$/\1/"; }
REVALIDATE=$(grep -E '^REVALIDATE_SECRET=' web/.env.local 2>/dev/null | cut -d= -f2- || true)
REVALIDATE=${REVALIDATE:-$(openssl rand -hex 16)}
RABBIT=$(openssl rand -hex 12)
kubectl -n $NS create secret generic pulse-secrets \
  --from-literal=POSTGRES_SUPERUSER_PASSWORD="$(val POSTGRES_SUPERUSER_PASSWORD)" \
  --from-literal=CONTENT_DB_PASSWORD="$(val CONTENT_DB_PASSWORD)" \
  --from-literal=INGESTION_DB_PASSWORD="$(val INGESTION_DB_PASSWORD)" \
  --from-literal=ASSISTANT_DB_PASSWORD="$(val ASSISTANT_DB_PASSWORD)" \
  --from-literal=PULSE_JWT_PRIVATE_KEY="$(val PULSE_JWT_PRIVATE_KEY)" \
  --from-literal=PULSE_JWT_PUBLIC_KEY="$(val PULSE_JWT_PUBLIC_KEY)" \
  --from-literal=ADMIN_PASSWORD_HASH="$(val ADMIN_PASSWORD_HASH)" \
  --from-literal=REVALIDATE_SECRET="$REVALIDATE" \
  --from-literal=RABBITMQ_PASSWORD="$RABBIT" \
  --from-literal=RABBITMQ_AMQP_URL="amqp://pulse:$RABBIT@pulse-rabbitmq.$NS.svc.cluster.local:5672/" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "==> 5/7 helm upgrade --install"
helm upgrade --install pulse $CHART -n $NS -f $CHART/values-kind.yaml ${EXTRA_SETS[@]+"${EXTRA_SETS[@]}"} --wait --timeout 10m

echo "==> 6/7 helm test"
helm test pulse -n $NS --logs | tail -3

echo "==> 7/7 warm the website (pages were built without a backend inside docker build)"
for i in $(seq 1 30); do curl -sf -o /dev/null http://localhost:30000/robots.txt && break; sleep 2; done
curl -s -X POST -H "x-revalidate-secret: $REVALIDATE" -H 'content-type: application/json' \
  -d '{"tags":["home","posts","section:TECH","section:FOOTBALL","section:AI"]}' http://localhost:30000/api/revalidate >/dev/null || true

kubectl get pods,svc,hpa,scaledobject -n $NS
echo
echo "Website:          http://localhost:30000"
echo "content-service:  http://localhost:30081/api/v1/posts   (Swagger: /swagger-ui.html)"
echo "Load test + watch autoscaling: ./scripts/k8s-load-test.sh"
