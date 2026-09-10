#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
set -a
source "$ROOT/.env"
set +a

JDBC=${DB_URL#jdbc:postgresql://}
HOSTPORT=${JDBC%%/*}
DB_FROM_URL=${JDBC#*/}
DB_FROM_URL=${DB_FROM_URL%%\?*}
DB_HOST=${HOSTPORT%%:*}
DB_PORT=${HOSTPORT##*:}
[[ "$DB_PORT" != "$HOSTPORT" ]] || DB_PORT=5432
DB_NAME=${DB_NAME:-$DB_FROM_URL}

mkdir -p "$ROOT/backups"
chmod 700 "$ROOT/backups"
OUTPUT="$ROOT/backups/vinted-$(date +%Y%m%d-%H%M%S).dump"
PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" --format=custom --file="$OUTPUT"
chmod 600 "$OUTPUT"
echo "$OUTPUT"
