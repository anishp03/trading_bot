#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LIVE_BACKEND_DIR="$PROJECT_ROOT/LiveBackend"
WINDOWS_RELEASE_DIR="$PROJECT_ROOT/release/backend-windows"

"$PROJECT_ROOT/scripts/build-backend-release.sh"

mkdir -p "$LIVE_BACKEND_DIR"

cp "$WINDOWS_RELEASE_DIR/tradingbot-backend.jar" "$LIVE_BACKEND_DIR/tradingbot-backend.jar"
cp "$WINDOWS_RELEASE_DIR/run-backend-release.ps1" "$LIVE_BACKEND_DIR/run-backend-release.ps1"
cp "$WINDOWS_RELEASE_DIR/install-windows-scheduled-task.ps1" "$LIVE_BACKEND_DIR/install-windows-scheduled-task.ps1"
cp "$WINDOWS_RELEASE_DIR/uninstall-windows-scheduled-task.ps1" "$LIVE_BACKEND_DIR/uninstall-windows-scheduled-task.ps1"
cp "$WINDOWS_RELEASE_DIR/bootstrap-windows-pc.ps1" "$LIVE_BACKEND_DIR/bootstrap-windows-pc.ps1"
cp "$WINDOWS_RELEASE_DIR/.env.example" "$LIVE_BACKEND_DIR/.env.example"

COMMIT_SHA="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
BUILD_TIME="$(date '+%Y-%m-%d %H:%M:%S %Z')"
JAR_SHA="$(shasum -a 256 "$LIVE_BACKEND_DIR/tradingbot-backend.jar" | awk '{print $1}')"
JAR_SIZE="$(wc -c < "$LIVE_BACKEND_DIR/tradingbot-backend.jar" | tr -d ' ')"

cat > "$LIVE_BACKEND_DIR/BUILD_INFO.txt" <<INFO
LiveBackend transport package

Built from local backend release: $BUILD_TIME
Source commit at package creation: $COMMIT_SHA
JAR SHA-256: $JAR_SHA
JAR size: $JAR_SIZE bytes

Live DB, .env, broker/API keys, logs, and runtime state are intentionally not included in GitHub.
INFO

echo "LiveBackend transport package refreshed at $LIVE_BACKEND_DIR"
