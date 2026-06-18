#!/usr/bin/env bash
set -euo pipefail

SOFTWARE_ROOT="${SOFTWARE_ROOT:-/Users/anishpatel/Documents/SoftwareProject}"
LIVE_BACKEND_DIR="${LIVE_BACKEND_DIR:-$SOFTWARE_ROOT/live_backend}"
LIVE_BACKEND_CODE_DIR="${LIVE_BACKEND_CODE_DIR:-$LIVE_BACKEND_DIR/backend}"
ENV_FILE="${TRADINGBOT_ENV_FILE:-$LIVE_BACKEND_DIR/.env}"
BUILD_ENV_FILE="$LIVE_BACKEND_DIR/.build.env"
JAR_PATH="${TRADINGBOT_JAR_PATH:-$LIVE_BACKEND_CODE_DIR/target/backend-0.0.1-SNAPSHOT-all.jar}"
SHARED_RUNTIME_DIR="${TRADINGBOT_RUNTIME_ROOT:-$SOFTWARE_ROOT/shared_runtime}"
LOG_FILE="${TRADINGBOT_BACKEND_RUN_LOG:-$LIVE_BACKEND_DIR/logs/run-live-backend.log}"
MAINTENANCE_FILE="$LIVE_BACKEND_DIR/.backend-maintenance"
RUN_DIR="$LIVE_BACKEND_DIR/run"
RUNNER_PID_FILE="$RUN_DIR/backend-runner.pid"
JAVA_PID_FILE="$RUN_DIR/backend-java.pid"
CAFFEINATE_PID_FILE="$RUN_DIR/backend-caffeinate.pid"

# Ignore an ambient dev DB override; live may only override this from live_backend/.env.
unset TRADINGBOT_DB_PATH

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

if [ -f "$BUILD_ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$BUILD_ENV_FILE"
  set +a
fi

TRADINGBOT_RUNTIME_ROOT="${TRADINGBOT_RUNTIME_ROOT:-$SHARED_RUNTIME_DIR}"
TRADINGBOT_RUNTIME_ROLE="${TRADINGBOT_RUNTIME_ROLE:-live}"
TRADINGBOT_DB_PATH="${TRADINGBOT_DB_PATH:-$SHARED_RUNTIME_DIR/db/tradingbot.db}"
if [ "$TRADINGBOT_DB_PATH" = "$LIVE_BACKEND_CODE_DIR/tradingbot.db" ]; then
  TRADINGBOT_DB_PATH="$SHARED_RUNTIME_DIR/db/tradingbot.db"
fi
TRADINGBOT_EQUITY_MARKET_DATA_DIR="${TRADINGBOT_EQUITY_MARKET_DATA_DIR:-$SHARED_RUNTIME_DIR/market_data}"
TRADINGBOT_FUTURES_DATA_DIR="${TRADINGBOT_FUTURES_DATA_DIR:-$SHARED_RUNTIME_DIR/market_data/futures}"
TRADINGBOT_LIVE_TRADE_CACHE_DIR="${TRADINGBOT_LIVE_TRADE_CACHE_DIR:-$SHARED_RUNTIME_DIR/data/live_trade_cache}"
TRADINGBOT_PORT="${TRADINGBOT_PORT:-7070}"
TRADINGBOT_BIND_HOST="${TRADINGBOT_BIND_HOST:-127.0.0.1}"
TRADINGBOT_BACKEND_RESTART_DELAY_SECONDS="${TRADINGBOT_BACKEND_RESTART_DELAY_SECONDS:-10}"
TRADINGBOT_BACKEND_HEALTH_TIMEOUT_SECONDS="${TRADINGBOT_BACKEND_HEALTH_TIMEOUT_SECONDS:-60}"

mkdir -p "$LIVE_BACKEND_DIR/logs" "$LIVE_BACKEND_CODE_DIR" "$RUN_DIR"
cd "$LIVE_BACKEND_CODE_DIR"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG_FILE"
}

backend_base_url() {
  printf 'http://127.0.0.1:%s' "$TRADINGBOT_PORT"
}

if [ ! -f "$TRADINGBOT_DB_PATH" ] && [ "${TRADINGBOT_ALLOW_EMPTY_SHARED_RUNTIME:-false}" != "true" ]; then
  log "Centralized runtime DB not found at $TRADINGBOT_DB_PATH."
  log "Seed it from the live DB with trading_bot/scripts/prepare-shared-runtime.sh --apply before starting live."
  log "Set TRADINGBOT_ALLOW_EMPTY_SHARED_RUNTIME=true only for isolated test runs."
  exit 1
fi

backend_health_ok() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 5 "$(backend_base_url)/api/system/health" >/dev/null 2>&1
}

wait_for_backend_health() {
  local elapsed=0
  while [ "$elapsed" -lt "$TRADINGBOT_BACKEND_HEALTH_TIMEOUT_SECONDS" ]; do
    if backend_health_ok; then
      log "Backend health check passed."
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  log "Backend health check did not pass within ${TRADINGBOT_BACKEND_HEALTH_TIMEOUT_SECONDS}s."
  return 1
}

JAVA_CMD=(
  java
  "-Dtradingbot.runtimeRoot=$TRADINGBOT_RUNTIME_ROOT"
  "-Dtradingbot.runtimeRole=$TRADINGBOT_RUNTIME_ROLE"
  "-Dtradingbot.db.path=$TRADINGBOT_DB_PATH"
  "-Dtradingbot.equityMarketDataDir=$TRADINGBOT_EQUITY_MARKET_DATA_DIR"
  "-Dtradingbot.futuresDataDir=$TRADINGBOT_FUTURES_DATA_DIR"
  "-Dtradingbot.liveTradeCacheDir=$TRADINGBOT_LIVE_TRADE_CACHE_DIR"
  "-Dtradingbot.version=${TRADINGBOT_VERSION:-live-backend}"
  "-Dtradingbot.build=${TRADINGBOT_BUILD:-manual}"
  "-Dtradingbot.bindHost=$TRADINGBOT_BIND_HOST"
  "-Dtradingbot.port=$TRADINGBOT_PORT"
  "-Dtradingbot.requireAppAuth=${TRADINGBOT_REQUIRE_APP_AUTH:-false}"
  "-Dtradingbot.defaultAccountEmail=${TRADINGBOT_DEFAULT_ACCOUNT_EMAIL:-patelanish203@gmail.com}"
  "-Dtradingbot.corsOrigins=${TRADINGBOT_CORS_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080}"
  -jar
  "${TRADINGBOT_JAR_PATH:-$JAR_PATH}"
)

STOP_REQUESTED=0
CHILD_PID=""
CAFFEINATE_PID=""
BACKEND_RESTART_COUNT=0

cleanup_pid_files() {
  rm -f "$RUNNER_PID_FILE" "$JAVA_PID_FILE" "$CAFFEINATE_PID_FILE"
}

shutdown() {
  STOP_REQUESTED=1
  if [ -n "$CAFFEINATE_PID" ]; then
    kill "$CAFFEINATE_PID" >/dev/null 2>&1 || true
  fi
  if [ -n "$CHILD_PID" ]; then
    kill "$CHILD_PID" >/dev/null 2>&1 || true
  fi
}

printf '%s\n' "$$" > "$RUNNER_PID_FILE"
trap shutdown TERM INT
trap cleanup_pid_files EXIT

while [ "$STOP_REQUESTED" -eq 0 ]; do
  if [ -f "$MAINTENANCE_FILE" ]; then
    log "Maintenance marker found; live backend runner is exiting."
    exit 0
  fi

  log "Starting live backend process."
  "${JAVA_CMD[@]}" &
  CHILD_PID="$!"
  printf '%s\n' "$CHILD_PID" > "$JAVA_PID_FILE"
  if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -dimsu -w "$CHILD_PID" &
    CAFFEINATE_PID="$!"
    printf '%s\n' "$CAFFEINATE_PID" > "$CAFFEINATE_PID_FILE"
  fi

  if wait_for_backend_health; then
    if [ "$BACKEND_RESTART_COUNT" -gt 0 ]; then
      log "Backend recovered after unexpected shutdown; live bot remains manual."
    else
      log "Backend is online; live bot remains manual."
    fi
  fi

  while kill -0 "$CHILD_PID" >/dev/null 2>&1; do
    if [ -f "$MAINTENANCE_FILE" ]; then
      log "Maintenance marker found; stopping live backend process."
      kill "$CHILD_PID" >/dev/null 2>&1 || true
      break
    fi
    sleep 5
  done

  set +e
  wait "$CHILD_PID"
  exit_code="$?"
  set -e
  if [ -n "$CAFFEINATE_PID" ]; then
    kill "$CAFFEINATE_PID" >/dev/null 2>&1 || true
    wait "$CAFFEINATE_PID" >/dev/null 2>&1 || true
  fi
  CHILD_PID=""
  CAFFEINATE_PID=""
  rm -f "$JAVA_PID_FILE" "$CAFFEINATE_PID_FILE"

  if [ "$STOP_REQUESTED" -eq 1 ] || [ -f "$MAINTENANCE_FILE" ]; then
    log "Live backend runner stopped."
    exit 0
  fi

  log "Live backend process exited with code $exit_code; restarting in ${TRADINGBOT_BACKEND_RESTART_DELAY_SECONDS}s."
  BACKEND_RESTART_COUNT=$((BACKEND_RESTART_COUNT + 1))
  sleep "$TRADINGBOT_BACKEND_RESTART_DELAY_SECONDS"
done
