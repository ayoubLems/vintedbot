#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

command -v brew >/dev/null || { echo "Homebrew requis: https://brew.sh"; exit 1; }
brew bundle
brew services start postgresql@14

JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
java -version

[[ -f .env ]] || ./scripts/init-env.sh
chmod 600 .env
./scripts/init-db.sh
./scripts/mvn.sh clean test
./scripts/mvn.sh package -DskipTests
echo "Installation terminée. Démarrage: ./deploy/run-local.sh"
