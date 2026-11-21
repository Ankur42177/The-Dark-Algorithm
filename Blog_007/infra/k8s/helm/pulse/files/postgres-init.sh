#!/usr/bin/env bash
# =====================================================================
# Runs ONCE when the Postgres container starts with an EMPTY data volume.
# Creates one database + one login user per service ("database per service").
#
# Each service can only log into ITS OWN database. That makes the rule
# "never read another service's tables" impossible to break by accident.
#
# To re-run it: docker compose ... down -v   (-v deletes the volume = ALL data)
# =====================================================================
set -euo pipefail   # stop on the first error, on unset variables, and on failures inside pipes

create_db() {
  local db="$1" user="$2" password="$3"
  echo "Creating database ${db} owned by ${user}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE USER ${user} WITH PASSWORD '${password}';
    CREATE DATABASE ${db} OWNER ${user};
    REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
SQL
}

create_db content_db   content   "$CONTENT_DB_PASSWORD"
create_db ingestion_db ingestion "$INGESTION_DB_PASSWORD"
create_db assistant_db assistant "$ASSISTANT_DB_PASSWORD"

# pgvector must be enabled by a superuser inside the database that uses it (Phase 8).
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname assistant_db \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
