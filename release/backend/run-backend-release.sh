#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${TRADINGBOT_APP_DIR:-/opt/tradingbot/backend}"
JAR_PATH="${TRADINGBOT_JAR_PATH:-$APP_DIR/tradingbot-backend.jar}"
DB_PATH="${TRADINGBOT_DB_PATH:-$APP_DIR/data/tradingbot.db}"

if [ -f "$APP_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$APP_DIR/.env"
  set +a
fi

mkdir -p "$APP_DIR/data" "$APP_DIR/logs"
cd "$APP_DIR"

exec java \
  -Dtradingbot.db.path="$DB_PATH" \
  -Dtradingbot.version="${TRADINGBOT_VERSION:-local-release}" \
  -Dtradingbot.build="${TRADINGBOT_BUILD:-manual}" \
  -Dtradingbot.bindHost="${TRADINGBOT_BIND_HOST:-127.0.0.1}" \
  -Dtradingbot.port="${TRADINGBOT_PORT:-7070}" \
  -jar "$JAR_PATH"
