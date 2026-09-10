#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 && -f "$1" ]] || { echo "Usage: $0 backup.dump"; exit 1; }
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

read -r -p "Remplacer le contenu de $DB_NAME ? [y/N] " ANSWER
[[ "$ANSWER" == y ]]
PGPASSWORD="$DB_PASSWORD" pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --no-owner "$1"
