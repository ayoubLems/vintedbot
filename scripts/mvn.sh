#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=3.9.9
TOOLS="$ROOT/.tools"
MAVEN="$TOOLS/apache-maven-$VERSION"

if [[ "$(uname -s)" == Darwin ]] && command -v brew >/dev/null; then
  JAVA17_PREFIX=$(brew --prefix openjdk@17 2>/dev/null || true)
  JAVA17_HOME="$JAVA17_PREFIX/libexec/openjdk.jdk/Contents/Home"
  if [[ -x "$JAVA17_HOME/bin/java" ]]; then
    JAVA_HOME="$JAVA17_HOME"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F '[".]' '/version/ {print $2}')
[[ "$JAVA_MAJOR" == 17 ]] || { echo "Java 17 requis. Exécuter ./scripts/setup-macos.sh"; exit 1; }

if [[ ! -x "$MAVEN/bin/mvn" ]]; then
  mkdir -p "$TOOLS"
  ARCHIVE="$TOOLS/apache-maven-$VERSION-bin.tar.gz"
  BASE="https://archive.apache.org/dist/maven/maven-3/$VERSION/binaries/apache-maven-$VERSION-bin.tar.gz"
  curl --fail --location --proto '=https' --tlsv1.2 "$BASE" -o "$ARCHIVE"
  curl --fail --location --proto '=https' --tlsv1.2 "$BASE.sha512" -o "$ARCHIVE.sha512"
  EXPECTED=$(tr -d '[:space:]' < "$ARCHIVE.sha512")
  ACTUAL=$(shasum -a 512 "$ARCHIVE" | awk '{print $1}')
  [[ "$EXPECTED" == "$ACTUAL" ]]
  tar -xzf "$ARCHIVE" -C "$TOOLS"
  rm "$ARCHIVE" "$ARCHIVE.sha512"
fi

exec "$MAVEN/bin/mvn" "$@"
