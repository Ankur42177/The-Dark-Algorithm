#!/usr/bin/env bash
# =====================================================================
# Drive load at content-service on kind and record how the HPA reacts.
#   ./scripts/k8s-load-test.sh [duration=3m] [vus=60]
# Writes a timeline (every 10 s: HPA target %, desired/current replicas, pod list) to
# $TMPDIR/pulse-hpa-<timestamp>.log (override with LOG_DIR=...), then keeps watching 3 more minutes to see the scale-DOWN.
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
DUR=${1:-3m}
VUS=${2:-60}
NS=pulse
LOG_DIR=${LOG_DIR:-${TMPDIR:-/tmp}}
mkdir -p "$LOG_DIR"
LOG=$LOG_DIR/pulse-hpa-$(date +%Y%m%d-%H%M%S).log
watch_hpa() {
  local start=$(date +%s)
  while true; do
    printf '%4ss  %s  pods=%s\n' "$(( $(date +%s) - start ))" \
      "$(kubectl -n $NS get hpa content-service --no-headers | awk '{print "cpu="$3, "min="$4, "max="$5, "replicas="$6}')" \
      "$(kubectl -n $NS get pods -l app.kubernetes.io/name=content-service --no-headers | awk '{printf "%s(%s) ", substr($1,length($1)-4), $2}')"
    sleep 10
  done
}
watch_hpa | tee "$LOG" &
WATCHER=$!
trap 'kill $WATCHER 2>/dev/null || true' EXIT
k6 run --quiet --vus "$VUS" --duration "$DUR" -e BASE=http://localhost:30081 load-tests/k6/phase3-cache.js || true
echo "---- load stopped; watching scale-down for 3 minutes" | tee -a "$LOG"
sleep 180
echo "timeline saved to $LOG"
