#!/usr/bin/env bash
# Runs the bot locally, loading env from .env in the project root.
# Used by the launchd/systemd wrappers and handy for manual runs.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "$(uname -s)" == Darwin ]]; then
  JAVA17_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  if [[ ! -x "$JAVA17_HOME/bin/java" ]] && command -v brew >/dev/null; then
    JAVA17_PREFIX=$(brew --prefix openjdk@17 2>/dev/null || true)
    JAVA17_HOME="$JAVA17_PREFIX/libexec/openjdk.jdk/Contents/Home"
  fi
  if [[ -x "$JAVA17_HOME/bin/java" ]]; then
    export JAVA_HOME="$JAVA17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F '[".]' '/version/ {print $2}')
[[ "$JAVA_MAJOR" == 17 ]] || { echo "Java 17 requis. Exécuter ./scripts/setup-macos.sh"; exit 1; }

# Load .env if present
if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

: "${DB_URL:=jdbc:postgresql://localhost:5432/vinted}"
: "${DB_USER:=vinted}"
: "${DB_PASSWORD:=vinted}"
: "${IMAGE_CACHE_DIR:=/tmp/vinted_images}"
# Auto-detect Chrome on macOS for the Selenium fallback.
if [[ -z "${CHROME_BINARY_PATH:-}" && -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
  export CHROME_BINARY_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
fi
export DB_URL DB_USER DB_PASSWORD IMAGE_CACHE_DIR

JAR=$(ls target/vinted-telegram-bot-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "Building jar first..."; ./scripts/mvn.sh -q package -DskipTests
  JAR=$(ls target/vinted-telegram-bot-*.jar | head -1)
fi

exec java -jar "$JAR"
