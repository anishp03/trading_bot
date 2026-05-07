#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="/Users/anishpatel/Documents/SoftwareProject/trading_bot"
BACKEND_DIR="$PROJECT_ROOT/backend"

load_env_file() {
  local env_file="$1"
  if [ -f "$env_file" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$env_file"
    set +a
  fi
}

load_env_file "$PROJECT_ROOT/.env.local"
load_env_file "$BACKEND_DIR/.env.local"

mkdir -p "$BACKEND_DIR/logs"
cd "$BACKEND_DIR"

if command -v caffeinate >/dev/null 2>&1; then
  exec caffeinate -dimsu ./mvnw -q compile exec:java -Dexec.mainClass=com.tradingbot.MainServer
fi

exec ./mvnw -q compile exec:java -Dexec.mainClass=com.tradingbot.MainServer
