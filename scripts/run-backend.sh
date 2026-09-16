#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/production_backend"
DEV_RUNTIME_DIR="${TRADINGBOT_DEV_RUNTIME_ROOT:-$PROJECT_ROOT/dev_runtime}"

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

export TRADINGBOT_BIND_HOST="${TRADINGBOT_BIND_HOST:-127.0.0.1}"
export TRADINGBOT_PORT="${TRADINGBOT_PORT:-7071}"
export TRADINGBOT_RUNTIME_ROOT="${TRADINGBOT_RUNTIME_ROOT:-$DEV_RUNTIME_DIR}"
export TRADINGBOT_RUNTIME_ROLE="${TRADINGBOT_RUNTIME_ROLE:-dev}"
export TRADINGBOT_DB_PATH="${TRADINGBOT_DB_PATH:-$TRADINGBOT_RUNTIME_ROOT/db/tradingbot.db}"
export TRADINGBOT_EQUITY_MARKET_DATA_DIR="${TRADINGBOT_EQUITY_MARKET_DATA_DIR:-$TRADINGBOT_RUNTIME_ROOT/market_data}"
export TRADINGBOT_FUTURES_DATA_DIR="${TRADINGBOT_FUTURES_DATA_DIR:-$TRADINGBOT_RUNTIME_ROOT/market_data/futures}"
export TRADINGBOT_LIVE_TRADE_CACHE_DIR="${TRADINGBOT_LIVE_TRADE_CACHE_DIR:-$TRADINGBOT_RUNTIME_ROOT/data/live_trade_cache}"

mkdir -p "$(dirname "$TRADINGBOT_DB_PATH")" \
  "$TRADINGBOT_EQUITY_MARKET_DATA_DIR" \
  "$TRADINGBOT_FUTURES_DATA_DIR" \
  "$TRADINGBOT_LIVE_TRADE_CACHE_DIR"

if command -v caffeinate >/dev/null 2>&1; then
  exec caffeinate -dimsu ./mvnw -q compile exec:java \
    -Dexec.mainClass=com.tradingbot.MainServer \
    -Dtradingbot.runtimeRoot="$TRADINGBOT_RUNTIME_ROOT" \
    -Dtradingbot.runtimeRole="$TRADINGBOT_RUNTIME_ROLE" \
    -Dtradingbot.db.path="$TRADINGBOT_DB_PATH" \
    -Dtradingbot.equityMarketDataDir="$TRADINGBOT_EQUITY_MARKET_DATA_DIR" \
    -Dtradingbot.futuresDataDir="$TRADINGBOT_FUTURES_DATA_DIR" \
    -Dtradingbot.liveTradeCacheDir="$TRADINGBOT_LIVE_TRADE_CACHE_DIR"
fi

exec ./mvnw -q compile exec:java \
  -Dexec.mainClass=com.tradingbot.MainServer \
  -Dtradingbot.runtimeRoot="$TRADINGBOT_RUNTIME_ROOT" \
  -Dtradingbot.runtimeRole="$TRADINGBOT_RUNTIME_ROLE" \
  -Dtradingbot.db.path="$TRADINGBOT_DB_PATH" \
  -Dtradingbot.equityMarketDataDir="$TRADINGBOT_EQUITY_MARKET_DATA_DIR" \
  -Dtradingbot.futuresDataDir="$TRADINGBOT_FUTURES_DATA_DIR" \
  -Dtradingbot.liveTradeCacheDir="$TRADINGBOT_LIVE_TRADE_CACHE_DIR"
