#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
set -a
source "$ROOT/.env"
set +a

psql postgres -v db_user="$DB_USER" -v db_password="$DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'db_user') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'db_user', :'db_password') \gexec
SQL

if ! psql postgres -v db_name="$DB_NAME" -At <<'SQL' | grep -q 1; then
SELECT 1 FROM pg_database WHERE datname = :'db_name';
SQL
  createdb --owner="$DB_USER" "$DB_NAME"
fi
