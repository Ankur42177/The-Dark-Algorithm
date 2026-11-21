#!/usr/bin/env bash
# =====================================================================
# Creates the BCrypt hash of the admin password and stores it in .env as ADMIN_PASSWORD_HASH.
# The password itself is never written anywhere.
#   ./scripts/hash-password.sh            (run from Blog_007/)
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
CP_FILE=$(mktemp)
./mvnw -q -pl services/content-service dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null
read -r -s -p "Admin password (min 12 chars): " PW; echo
HASH=$(printf '%s' "$PW" | java -cp "$(cat "$CP_FILE")" scripts/HashPassword.java)
rm -f "$CP_FILE"
# Single quotes: Docker Compose must not treat the '$' characters in the hash as variables.
grep -v '^ADMIN_PASSWORD_HASH=' .env > .env.tmp || true
echo "ADMIN_PASSWORD_HASH='$HASH'" >> .env.tmp && mv .env.tmp .env && chmod 600 .env
echo "Saved ADMIN_PASSWORD_HASH to .env (restart content-service)."
