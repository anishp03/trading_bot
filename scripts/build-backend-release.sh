#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
RELEASE_DIR="$PROJECT_ROOT/release/backend"
WINDOWS_RELEASE_DIR="$PROJECT_ROOT/release/backend-windows"

if ! command -v java >/dev/null 2>&1; then
  echo "Java is missing. On Ubuntu/Debian install dependencies with:" >&2
  echo "  sudo apt-get update" >&2
  echo "  sudo apt-get install -y openjdk-17-jdk git curl ca-certificates sqlite3 tar unzip" >&2
  echo "Or run:" >&2
  echo "  sudo ./scripts/bootstrap-linux-pc.sh" >&2
  exit 1
fi

if ! java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])\.'; then
  echo "Java 17+ is required. On Ubuntu/Debian install it with:" >&2
  echo "  sudo apt-get update" >&2
  echo "  sudo apt-get install -y openjdk-17-jdk" >&2
  exit 1
fi

cd "$BACKEND_DIR"
./mvnw clean package

mkdir -p "$RELEASE_DIR"
mkdir -p "$WINDOWS_RELEASE_DIR"
cp "$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT-all.jar" "$RELEASE_DIR/tradingbot-backend.jar"
cp "$BACKEND_DIR/target/backend-0.0.1-SNAPSHOT-all.jar" "$WINDOWS_RELEASE_DIR/tradingbot-backend.jar"
cp "$PROJECT_ROOT/scripts/run-backend-release.sh" "$RELEASE_DIR/run-backend-release.sh"
cp "$PROJECT_ROOT/scripts/tradingbot-backend.service" "$RELEASE_DIR/tradingbot-backend.service"
cp "$PROJECT_ROOT/scripts/install-linux-systemd-backend.sh" "$RELEASE_DIR/install-linux-systemd-backend.sh"
cp "$PROJECT_ROOT/scripts/bootstrap-linux-pc.sh" "$RELEASE_DIR/bootstrap-linux-pc.sh"
cp "$PROJECT_ROOT/.env.example" "$RELEASE_DIR/.env.example"
chmod +x "$RELEASE_DIR/run-backend-release.sh" "$RELEASE_DIR/install-linux-systemd-backend.sh" "$RELEASE_DIR/bootstrap-linux-pc.sh"

cp "$PROJECT_ROOT/scripts/run-backend-release.ps1" "$WINDOWS_RELEASE_DIR/run-backend-release.ps1"
cp "$PROJECT_ROOT/scripts/install-windows-scheduled-task.ps1" "$WINDOWS_RELEASE_DIR/install-windows-scheduled-task.ps1"
cp "$PROJECT_ROOT/scripts/uninstall-windows-scheduled-task.ps1" "$WINDOWS_RELEASE_DIR/uninstall-windows-scheduled-task.ps1"
cp "$PROJECT_ROOT/scripts/bootstrap-windows-pc.ps1" "$WINDOWS_RELEASE_DIR/bootstrap-windows-pc.ps1"
cp "$PROJECT_ROOT/.env.example" "$WINDOWS_RELEASE_DIR/.env.example"

cat > "$RELEASE_DIR/README.txt" <<'README'
Trading Bot Backend Release

Files:
- tradingbot-backend.jar: standalone Java backend
- run-backend-release.sh: local runner
- tradingbot-backend.service: systemd service template
- install-linux-systemd-backend.sh: installer for the Linux PC
- bootstrap-linux-pc.sh: dependency installer for Ubuntu/Debian
- .env.example: example env config

Recommended install path on Linux:
/opt/tradingbot/backend

Basic run:
java -jar tradingbot-backend.jar

Systemd:
1. Create /opt/tradingbot/backend
2. Copy these files there
3. Copy tradingbot-backend.service to /etc/systemd/system/
4. systemctl daemon-reload
5. systemctl enable --now tradingbot-backend
README

echo "Backend release written to $RELEASE_DIR"
echo "Windows backend release written to $WINDOWS_RELEASE_DIR"
