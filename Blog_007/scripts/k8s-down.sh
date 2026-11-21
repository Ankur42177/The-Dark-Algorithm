#!/usr/bin/env bash
# Deletes the whole kind cluster (all pods, volumes, images loaded into it). Your Docker Compose stack is untouched.
# Recreate with ./scripts/k8s-up.sh
set -euo pipefail
kind delete cluster --name pulse
