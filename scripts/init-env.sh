#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE="$ROOT/.env"
[[ ! -e "$ENV_FILE" ]] || { echo ".env existe déjà"; exit 1; }

read -r -p "BOT_USERNAME: " BOT_USERNAME
read -r -s -p "BOT_TOKEN: " BOT_TOKEN
echo
read -r -p "DB_NAME [vinted]: " DB_NAME
read -r -p "DB_USER [vinted]: " DB_USER
read -r -s -p "DB_PASSWORD: " DB_PASSWORD
echo
DB_NAME=${DB_NAME:-vinted}
DB_USER=${DB_USER:-vinted}

umask 077
{
  printf 'BOT_TOKEN=%q\n' "$BOT_TOKEN"
  printf 'BOT_USERNAME=%q\n' "$BOT_USERNAME"
  printf 'DB_NAME=%q\n' "$DB_NAME"
  printf 'DB_URL=%q\n' "jdbc:postgresql://localhost:5432/$DB_NAME"
  printf 'DB_USER=%q\n' "$DB_USER"
  printf 'DB_PASSWORD=%q\n' "$DB_PASSWORD"
  printf '%s\n' 'VINTED_PROXY=' 'MONITOR_ENABLED=true' 'MONITOR_INTERVAL_MS=60000' 'MONITOR_BACKOFF_MS=900000' 'SELENIUM_ENABLED=false' 'CHROME_BINARY_PATH=' 'CHROME_DRIVER_PATH=' 'IMAGE_CACHE_DIR=/tmp/vinted_images'
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo ".env créé avec permissions 600"
