#!/bin/bash
set -euo pipefail

echo "Starting Trading Bot..."

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/production_backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
SCRIPTS_DIR="$ROOT_DIR/scripts"
LIVE_BACKEND_DIR="$ROOT_DIR/live_backend"
WIP_BACKEND_PORT="${WIP_BACKEND_PORT:-7071}"
WIP_FRONTEND_PORT="${WIP_FRONTEND_PORT:-5174}"
DEV_RUNTIME_DIR="${TRADINGBOT_DEV_RUNTIME_ROOT:-$ROOT_DIR/dev_runtime}"
BACKEND_PID=""
FRONTEND_PID=""

cleanup_current_run() {
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
  fi

  if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
    kill "$FRONTEND_PID" 2>/dev/null || true
  fi
}

trap cleanup_current_run EXIT INT TERM

if [ ! -x "$BACKEND_DIR/mvnw" ]; then
  echo "Maven wrapper not found in $BACKEND_DIR."
  exit 1
fi

if [ ! -x "$SCRIPTS_DIR/run-backend.sh" ]; then
  echo "Backend launcher not found in $SCRIPTS_DIR."
  exit 1
fi

echo "Starting local verification backend on http://127.0.0.1:$WIP_BACKEND_PORT ..."
TRADINGBOT_BIND_HOST=127.0.0.1 \
TRADINGBOT_PORT="$WIP_BACKEND_PORT" \
TRADINGBOT_DEV_RUNTIME_ROOT="$DEV_RUNTIME_DIR" \
TRADINGBOT_RUNTIME_ROOT="${TRADINGBOT_RUNTIME_ROOT:-$DEV_RUNTIME_DIR}" \
TRADINGBOT_RUNTIME_ROLE=dev \
TRADINGBOT_REQUIRE_APP_AUTH=false \
TRADINGBOT_DEFAULT_ACCOUNT_EMAIL="${TRADINGBOT_DEFAULT_ACCOUNT_EMAIL:-patelanish203@gmail.com}" \
TRADINGBOT_CORS_ORIGINS="http://localhost:$WIP_FRONTEND_PORT,http://127.0.0.1:$WIP_FRONTEND_PORT,http://localhost:5173,http://127.0.0.1:5173" \
TRADINGBOT_ENABLE_BACKEND_UPDATE=true \
TRADINGBOT_BACKEND_UPDATE_SCRIPT="$SCRIPTS_DIR/update-live-backend.sh" \
TRADINGBOT_BACKEND_UPDATE_LOG="$LIVE_BACKEND_DIR/logs/update-backend.log" \
"$SCRIPTS_DIR/run-backend.sh" &
BACKEND_PID=$!

echo "Starting frontend..."
cd "$FRONTEND_DIR" || exit 1

echo "Installing frontend dependencies..."
npm install

VITE_API_BASE_URL="http://127.0.0.1:$WIP_BACKEND_PORT" \
npm run dev -- --host 127.0.0.1 --port "$WIP_FRONTEND_PORT" --strictPort &
FRONTEND_PID=$!

echo ""
echo "Trading Bot local verification workspace is starting..."
echo "Production backend source: $BACKEND_DIR"
echo "Local backend: http://127.0.0.1:$WIP_BACKEND_PORT"
echo "Local frontend: http://127.0.0.1:$WIP_FRONTEND_PORT"
echo "Live backend stays separate at: http://127.0.0.1:7070"
echo ""
echo "    http://127.0.0.1:$WIP_FRONTEND_PORT"
echo ""
echo "Press Ctrl+C to stop both."

wait
