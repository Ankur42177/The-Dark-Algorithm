#!/usr/bin/env bash
# =====================================================================
# Generates an RSA-2048 key pair for signing JWTs (RS256) and stores both halves in .env
# as single-line Base64 (PKCS#8 private, X.509 public). Re-running ROTATES the keys:
# every existing login becomes invalid.
#   ./scripts/gen-jwt-keys.sh             (run from Blog_007/)
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TMP/private.pem" 2>/dev/null
PRIV=$(openssl pkcs8 -topk8 -nocrypt -in "$TMP/private.pem" -outform DER | base64 | tr -d '\n')
PUB=$(openssl pkey -in "$TMP/private.pem" -pubout -outform DER | base64 | tr -d '\n')
rm -rf "$TMP"
grep -vE '^PULSE_JWT_(PRIVATE|PUBLIC)_KEY=' .env > .env.tmp || true
{ echo "PULSE_JWT_PRIVATE_KEY=$PRIV"; echo "PULSE_JWT_PUBLIC_KEY=$PUB"; } >> .env.tmp
mv .env.tmp .env && chmod 600 .env
echo "Saved PULSE_JWT_PRIVATE_KEY / PULSE_JWT_PUBLIC_KEY to .env"
