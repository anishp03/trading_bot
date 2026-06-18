#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKING_PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"
SOFTWARE_ROOT="$(cd "$WORKING_PROJECT/.." && pwd)"
SOURCE_BACKEND="$WORKING_PROJECT/backend"
LIVE_BACKEND_DIR="${LIVE_BACKEND_DIR:-$SOFTWARE_ROOT/live_backend}"
LIVE_BACKEND_CODE_DIR="$LIVE_BACKEND_DIR/backend"
LIVE_MARKET_DATA_DIR="$LIVE_BACKEND_CODE_DIR/market_data"
SHARED_RUNTIME_DIR="${TRADINGBOT_RUNTIME_ROOT:-$SOFTWARE_ROOT/shared_runtime}"
LABEL="${TRADINGBOT_LAUNCH_LABEL:-com.tradingbot.backend}"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
RUN_SCRIPT_SOURCE="$SCRIPT_DIR/run-live-backend.sh"
RUN_SCRIPT_LIVE="$LIVE_BACKEND_DIR/bin/run-live-backend.sh"
ENV_FILE="$LIVE_BACKEND_DIR/.env"
BUILD_ENV_FILE="$LIVE_BACKEND_DIR/.build.env"
LOG_FILE="${TRADINGBOT_BACKEND_UPDATE_LOG:-$LIVE_BACKEND_DIR/logs/update-backend.log}"
LOCK_DIR="$LIVE_BACKEND_DIR/.update.lock"
MAINTENANCE_FILE="$LIVE_BACKEND_DIR/.backend-maintenance"
RUN_DIR="$LIVE_BACKEND_DIR/run"
RUNNER_PID_FILE="$RUN_DIR/backend-runner.pid"
JAVA_PID_FILE="$RUN_DIR/backend-java.pid"
CAFFEINATE_PID_FILE="$RUN_DIR/backend-caffeinate.pid"
RESTART_BACKEND=1
FROM_UI=0

for arg in "$@"; do
  case "$arg" in
    --no-restart)
      RESTART_BACKEND=0
      ;;
    --from-ui)
      FROM_UI=1
      ;;
    --preserve-live-db)
      ;;
    --promote-dev-db|--force-promote-dev-db|--with-live-db-backup)
      echo "DB promotion/backups are disabled. Backend updates are code-only and preserve the runtime DB." >&2
      exit 2
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$LIVE_BACKEND_DIR/logs"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG_FILE"
}

fail() {
  log "ERROR: $*"
  exit 1
}

acquire_lock() {
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    fail "Another backend update is already running."
  fi
  trap cleanup_update EXIT
}

cleanup_update() {
  rm -f "$MAINTENANCE_FILE"
  rm -rf "$LOCK_DIR"
}

pid_is_alive() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1
}

collect_pid_file() {
  local file="$1"
  [ -f "$file" ] || return 0
  awk 'NF == 1 && $1 ~ /^[0-9]+$/ { print $1 }' "$file" 2>/dev/null || true
}

collect_backend_process_pids() {
  ps -axo pid=,comm=,command= | awk \
    -v live_dir="$LIVE_BACKEND_DIR" \
    -v code_dir="$LIVE_BACKEND_CODE_DIR" \
    -v jar_path="$LIVE_BACKEND_CODE_DIR/target/backend-0.0.1-SNAPSHOT-all.jar" \
    -v self="$$" '
      $1 == self { next }
      $2 != "bash" && $2 != "java" { next }
      index($0, live_dir "/bin/run-live-backend.sh") > 0 { print $1; next }
      index($0, code_dir) > 0 && index($0, "backend-0.0.1-SNAPSHOT-all.jar") > 0 { print $1; next }
      index($0, jar_path) > 0 { print $1; next }
    ' 2>/dev/null || true
}

collect_project_cloudflared_pids() {
  ps -axo pid=,comm=,command= | awk \
    -v root="$SOFTWARE_ROOT" \
    -v live_dir="$LIVE_BACKEND_DIR" \
    -v self="$$" '
      $1 == self { next }
      $2 != "cloudflared" { next }
      tolower($0) ~ /cloudflared/ && (index($0, root) > 0 || index($0, live_dir) > 0 || tolower($0) ~ /tradingbot/) { print $1 }
    ' 2>/dev/null || true
}

collect_backend_port_pids() {
  local port="${TRADINGBOT_PORT:-7070}"
  command -v lsof >/dev/null 2>&1 || return 0
  lsof -tiTCP:"$port" -sTCP:LISTEN -n -P 2>/dev/null || true
}

unique_pids() {
  awk 'NF == 1 && $1 ~ /^[0-9]+$/ { seen[$1] = 1 } END { for (pid in seen) print pid }'
}

terminate_pids() {
  local label="$1"
  local pids="$2"
  [ -n "$pids" ] || return 0

  log "Stopping $label PIDs: $(printf '%s\n' "$pids" | tr '\n' ' ')"
  # shellcheck disable=SC2086
  kill $pids >/dev/null 2>&1 || true
}

kill_remaining_pids() {
  local label="$1"
  local pids="$2"
  local remaining=""
  local pid

  for pid in $pids; do
    if pid_is_alive "$pid"; then
      remaining="$remaining $pid"
    fi
  done

  [ -n "$remaining" ] || return 0
  log "Force stopping $label PIDs:$remaining"
  # shellcheck disable=SC2086
  kill -9 $remaining >/dev/null 2>&1 || true
}

ensure_launchagent_process_group_cleanup() {
  [ -f "$PLIST" ] || return 0

  if /usr/libexec/PlistBuddy -c "Print :AbandonProcessGroup" "$PLIST" >/dev/null 2>&1; then
    /usr/libexec/PlistBuddy -c "Set :AbandonProcessGroup false" "$PLIST" >/dev/null 2>&1 || true
  else
    /usr/libexec/PlistBuddy -c "Add :AbandonProcessGroup bool false" "$PLIST" >/dev/null 2>&1 || true
  fi
}

assert_backend_stopped() {
  local remaining
  local attempt

  for attempt in $(seq 1 10); do
    remaining="$(
      {
        collect_pid_file "$RUNNER_PID_FILE"
        collect_pid_file "$JAVA_PID_FILE"
        collect_pid_file "$CAFFEINATE_PID_FILE"
        collect_backend_process_pids
        collect_backend_port_pids
      } | unique_pids
    )"

    [ -n "$remaining" ] || return 0
    sleep 1
  done

  if [ -n "$remaining" ]; then
    fail "Backend update refused to continue because old backend processes are still running: $(printf '%s\n' "$remaining" | tr '\n' ' ')"
  fi
}

set_env_line() {
  local key="$1"
  local value="$2"
  local tmp_file

  tmp_file="$(mktemp "$LIVE_BACKEND_DIR/env.XXXXXX")"
  awk -v key="$key" -v value="$value" '
    BEGIN { updated = 0 }
    $0 ~ "^" key "=" {
      print key "=" value
      updated = 1
      next
    }
    { print }
    END {
      if (updated == 0) {
        print key "=" value
      }
    }
  ' "$ENV_FILE" > "$tmp_file"
  mv "$tmp_file" "$ENV_FILE"
}

remove_env_line() {
  local key="$1"
  local tmp_file

  [ -f "$ENV_FILE" ] || return 0
  tmp_file="$(mktemp "$LIVE_BACKEND_DIR/env.XXXXXX")"
  awk -v key="$key" '$0 !~ "^" key "=" { print }' "$ENV_FILE" > "$tmp_file"
  mv "$tmp_file" "$ENV_FILE"
}

ensure_live_env() {
  if [ ! -f "$ENV_FILE" ]; then
    cat > "$ENV_FILE" <<EOF
TRADINGBOT_DEFAULT_ACCOUNT_EMAIL=patelanish203@gmail.com
TRADINGBOT_BOOTSTRAP_ADMIN_NAME="Trading Bot Admin"
TRADINGBOT_BOOTSTRAP_ADMIN_EMAIL=patelanish203@gmail.com
TRADINGBOT_BOOTSTRAP_ADMIN_PASSWORD=change-this-before-launch
TRADINGBOT_REQUIRE_APP_AUTH=false
TRADINGBOT_BIND_HOST=127.0.0.1
TRADINGBOT_PORT=7070
TRADINGBOT_RUNTIME_ROOT=$SHARED_RUNTIME_DIR
TRADINGBOT_RUNTIME_ROLE=live
TRADINGBOT_DB_PATH=$SHARED_RUNTIME_DIR/db/tradingbot.db
TRADINGBOT_EQUITY_MARKET_DATA_DIR=$SHARED_RUNTIME_DIR/market_data
TRADINGBOT_FUTURES_DATA_DIR=$SHARED_RUNTIME_DIR/market_data/futures
TRADINGBOT_LIVE_TRADE_CACHE_DIR=$SHARED_RUNTIME_DIR/data/live_trade_cache
TRADINGBOT_CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080
TRADINGBOT_ENABLE_BACKEND_UPDATE=true
TRADINGBOT_BACKEND_UPDATE_SCRIPT=$WORKING_PROJECT/scripts/update-live-backend.sh
TRADINGBOT_BACKEND_UPDATE_LOG=$LOG_FILE
EOF
    chmod 600 "$ENV_FILE"
  else
    set_env_line "TRADINGBOT_ENABLE_BACKEND_UPDATE" "true"
    set_env_line "TRADINGBOT_BACKEND_UPDATE_SCRIPT" "$WORKING_PROJECT/scripts/update-live-backend.sh"
    set_env_line "TRADINGBOT_BACKEND_UPDATE_LOG" "$LOG_FILE"
    set_env_line "TRADINGBOT_RUNTIME_ROOT" "$SHARED_RUNTIME_DIR"
    set_env_line "TRADINGBOT_RUNTIME_ROLE" "live"
    set_env_line "TRADINGBOT_DB_PATH" "$SHARED_RUNTIME_DIR/db/tradingbot.db"
    set_env_line "TRADINGBOT_EQUITY_MARKET_DATA_DIR" "$SHARED_RUNTIME_DIR/market_data"
    set_env_line "TRADINGBOT_FUTURES_DATA_DIR" "$SHARED_RUNTIME_DIR/market_data/futures"
    set_env_line "TRADINGBOT_LIVE_TRADE_CACHE_DIR" "$SHARED_RUNTIME_DIR/data/live_trade_cache"
    set_env_line "TRADINGBOT_REQUIRE_APP_AUTH" "${TRADINGBOT_REQUIRE_APP_AUTH:-false}"
    remove_env_line "TRADINGBOT_AUTO_START_LIVE_BOT"
    remove_env_line "TRADINGBOT_KEEP_LIVE_BOT_ON"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_SYMBOL"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_SYMBOLS"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_STRATEGY_PRESET"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_FUNDED_PROFILE"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_ACCOUNT_ID"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_EXECUTION_MODE"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_ACCOUNT_SIZE"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_TRAILING_DRAWDOWN"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_DAILY_LOSS_LIMIT"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_MAX_RISK"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_MAX_CONTRACTS"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_COMMISSION"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_SLIPPAGE_TICKS"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_PROFIT_TARGET"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_MAX_OPEN_POSITIONS"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_MAX_AGGREGATE_CONTRACTS"
    remove_env_line "TRADINGBOT_LIVE_AUTOSTART_MAX_AGGREGATE_MINI_UNITS"
    chmod 600 "$ENV_FILE"
  fi
}

load_live_env() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
  fi

  TRADINGBOT_PORT="${TRADINGBOT_PORT:-7070}"
  TRADINGBOT_BIND_HOST="${TRADINGBOT_BIND_HOST:-127.0.0.1}"
  TRADINGBOT_RUNTIME_ROOT="${TRADINGBOT_RUNTIME_ROOT:-$SHARED_RUNTIME_DIR}"
  TRADINGBOT_RUNTIME_ROLE="${TRADINGBOT_RUNTIME_ROLE:-live}"
  TRADINGBOT_DB_PATH="${TRADINGBOT_DB_PATH:-$SHARED_RUNTIME_DIR/db/tradingbot.db}"
  TRADINGBOT_EQUITY_MARKET_DATA_DIR="${TRADINGBOT_EQUITY_MARKET_DATA_DIR:-$SHARED_RUNTIME_DIR/market_data}"
  TRADINGBOT_FUTURES_DATA_DIR="${TRADINGBOT_FUTURES_DATA_DIR:-$SHARED_RUNTIME_DIR/market_data/futures}"
  TRADINGBOT_LIVE_TRADE_CACHE_DIR="${TRADINGBOT_LIVE_TRADE_CACHE_DIR:-$SHARED_RUNTIME_DIR/data/live_trade_cache}"
}

build_backend() {
  [ -d "$SOURCE_BACKEND" ] || fail "Source backend folder does not exist: $SOURCE_BACKEND"
  [ -x "$SOURCE_BACKEND/mvnw" ] || chmod +x "$SOURCE_BACKEND/mvnw"

  log "Building backend from $SOURCE_BACKEND"
  cd "$SOURCE_BACKEND"
  if [ "${TRADINGBOT_UPDATE_SKIP_TESTS:-false}" = "true" ]; then
    run_source_maven -q -DskipTests clean package
  else
    run_source_maven -q clean package
  fi

  [ -f "$SOURCE_BACKEND/target/backend-0.0.1-SNAPSHOT-all.jar" ] || fail "Build did not produce the shaded backend jar."
}

run_source_maven() {
  env \
    -u TRADINGBOT_RUNTIME_ROOT \
    -u TRADINGBOT_RUNTIME_ROLE \
    -u TRADINGBOT_DB_PATH \
    -u TRADINGBOT_EQUITY_MARKET_DATA_DIR \
    -u TRADINGBOT_FUTURES_DATA_DIR \
    -u TRADINGBOT_LIVE_TRADE_CACHE_DIR \
    ./mvnw "$@"
}

prepare_live_backend() {
  mkdir -p "$LIVE_BACKEND_DIR/bin" "$LIVE_BACKEND_DIR/logs" "$RUN_DIR"
  cp "$RUN_SCRIPT_SOURCE" "$RUN_SCRIPT_LIVE"
  chmod +x "$RUN_SCRIPT_LIVE"
  ensure_live_env
  load_live_env
  ensure_launchagent_process_group_cleanup
}

write_build_env() {
  local build_id
  build_id="$(date '+%Y%m%d-%H%M%S')"

  cat > "$BUILD_ENV_FILE" <<EOF
TRADINGBOT_VERSION=live-backend
TRADINGBOT_BUILD=$build_id
EOF
}

stop_backend() {
  local pids
  local cloudflared_pids

  log "Stopping current backend"
  touch "$MAINTENANCE_FILE"
  ensure_launchagent_process_group_cleanup
  launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true

  if command -v screen >/dev/null 2>&1; then
    screen -S tradingbot-backend -X quit >/dev/null 2>&1 || true
  fi

  pids="$(
    {
      collect_pid_file "$CAFFEINATE_PID_FILE"
      collect_pid_file "$JAVA_PID_FILE"
      collect_pid_file "$RUNNER_PID_FILE"
      collect_backend_process_pids
      collect_backend_port_pids
    } | unique_pids
  )"
  terminate_pids "backend" "$pids"
  sleep 4

  pids="$(
    {
      collect_pid_file "$CAFFEINATE_PID_FILE"
      collect_pid_file "$JAVA_PID_FILE"
      collect_pid_file "$RUNNER_PID_FILE"
      collect_backend_process_pids
      collect_backend_port_pids
    } | unique_pids
  )"
  kill_remaining_pids "backend" "$pids"
  sleep 1

  cloudflared_pids="$(collect_project_cloudflared_pids | unique_pids)"
  if [ -n "$cloudflared_pids" ]; then
    terminate_pids "project-owned cloudflared tunnel" "$cloudflared_pids"
    sleep 2
    cloudflared_pids="$(collect_project_cloudflared_pids | unique_pids)"
    kill_remaining_pids "project-owned cloudflared tunnel" "$cloudflared_pids"
  fi

  rm -f "$RUNNER_PID_FILE" "$JAVA_PID_FILE" "$CAFFEINATE_PID_FILE"
  assert_backend_stopped
}

replace_backend_copy() {
  log "Copying trading_bot/backend into live_backend/backend"
  mkdir -p "$LIVE_BACKEND_CODE_DIR"

  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete \
      --exclude 'tradingbot.db' \
      --exclude 'tradingbot.db-*' \
      --exclude '*.db' \
      --exclude '*.db-*' \
      --exclude '*.sqlite' \
      --exclude '*.sqlite3' \
      --exclude 'market_data/' \
      "$SOURCE_BACKEND/" "$LIVE_BACKEND_CODE_DIR/"
  else
    local market_data_backup=""
    if [ -d "$LIVE_MARKET_DATA_DIR" ]; then
      market_data_backup="$(mktemp -d "$LIVE_BACKEND_DIR/market-data-backup.XXXXXX")"
      cp -R "$LIVE_MARKET_DATA_DIR/." "$market_data_backup/"
    fi
    rm -rf "$LIVE_BACKEND_CODE_DIR"
    mkdir -p "$LIVE_BACKEND_CODE_DIR"
    cp -R "$SOURCE_BACKEND/." "$LIVE_BACKEND_CODE_DIR/"
    if [ -n "$market_data_backup" ]; then
      rm -rf "$LIVE_MARKET_DATA_DIR"
      mkdir -p "$LIVE_MARKET_DATA_DIR"
      cp -R "$market_data_backup/." "$LIVE_MARKET_DATA_DIR/"
      rm -rf "$market_data_backup"
    fi
    find "$LIVE_BACKEND_CODE_DIR" -maxdepth 1 -type f \( \
      -name 'tradingbot.db' -o \
      -name 'tradingbot.db-*' -o \
      -name '*.db' -o \
      -name '*.db-*' -o \
      -name '*.sqlite' -o \
      -name '*.sqlite3' \
    \) -delete 2>/dev/null || true
  fi

  chmod +x "$LIVE_BACKEND_CODE_DIR/mvnw" "$RUN_SCRIPT_LIVE"
  rm -f "$LIVE_BACKEND_DIR/tradingbot-backend.jar" "$LIVE_BACKEND_DIR/tradingbot-backend.jar.next"
  rm -rf "$LIVE_BACKEND_DIR/data"
  xattr -dr com.apple.quarantine "$LIVE_BACKEND_DIR" >/dev/null 2>&1 || true
  xattr -dr com.apple.provenance "$LIVE_BACKEND_DIR" >/dev/null 2>&1 || true
}

start_backend() {
  rm -f "$MAINTENANCE_FILE"
  if [ -f "$PLIST" ]; then
    log "Starting backend with LaunchAgent $LABEL"
    launchctl bootstrap "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true
    launchctl kickstart -k "gui/$(id -u)/$LABEL"
  else
    log "LaunchAgent plist not found; starting backend directly for this session"
    nohup /bin/bash "$RUN_SCRIPT_LIVE" >> "$LIVE_BACKEND_DIR/logs/standalone.out.log" 2>> "$LIVE_BACKEND_DIR/logs/standalone.err.log" &
  fi
}

wait_for_health() {
  local port="${TRADINGBOT_PORT:-7070}"
  local url="http://127.0.0.1:$port/api/system/health"

  if ! command -v curl >/dev/null 2>&1; then
    log "curl not found; skipped health check"
    return 0
  fi

  for _ in $(seq 1 45); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "Backend health check passed at $url"
      return 0
    fi
    sleep 1
  done

  fail "Backend did not pass health check at $url"
}

main() {
  acquire_lock
  log "Starting live backend update"

  if [ "$FROM_UI" -eq 1 ]; then
    sleep 2
  fi

  build_backend
  prepare_live_backend
  write_build_env
  log "Code-only promotion mode; DB snapshot promotion is disabled."

  if [ "$RESTART_BACKEND" -eq 1 ]; then
    stop_backend
    log "Skipping live DB backup; runtime DB remains canonical at $TRADINGBOT_DB_PATH"
  fi

  replace_backend_copy
  log "Preserved runtime DB at $TRADINGBOT_DB_PATH"

  if [ "$RESTART_BACKEND" -eq 1 ]; then
    start_backend
    wait_for_health
    log "Live bot auto-start is disabled; start the bot manually from the Live Futures page."
  else
    log "Prepared live backend without restart"
  fi

  log "Live backend update complete"
}

main
