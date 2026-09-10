#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=3.9.9
TOOLS="$ROOT/.tools"
MAVEN="$TOOLS/apache-maven-$VERSION"

if [[ "$(uname -s)" == Darwin ]] && command -v brew >/dev/null && brew --prefix openjdk@17 >/dev/null 2>&1; then
  JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

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
