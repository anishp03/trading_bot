#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this on the Linux PC with sudo: sudo ./install-linux-systemd-backend.sh" >&2
  exit 1
fi

APP_DIR="/opt/tradingbot/backend"
SERVICE_FILE="/etc/systemd/system/tradingbot-backend.service"

if ! command -v java >/dev/null 2>&1; then
  echo "Java is missing. Install dependencies first:" >&2
  echo "  sudo apt-get update" >&2
  echo "  sudo apt-get install -y openjdk-17-jdk git curl ca-certificates sqlite3 tar unzip" >&2
  echo "Or from a cloned repo run:" >&2
  echo "  sudo ./scripts/bootstrap-linux-pc.sh" >&2
  exit 1
fi

if ! java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])\.'; then
  echo "Java 17+ is required. Install it with:" >&2
  echo "  sudo apt-get update" >&2
  echo "  sudo apt-get install -y openjdk-17-jdk" >&2
  exit 1
fi

if [ ! -f tradingbot-backend.jar ]; then
  echo "Missing tradingbot-backend.jar in the current directory." >&2
  echo "Build it from a cloned repo with:" >&2
  echo "  ./scripts/build-backend-release.sh" >&2
  echo "Then install from:" >&2
  echo "  cd release/backend && sudo ./install-linux-systemd-backend.sh" >&2
  exit 1
fi

id -u tradingbot >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin tradingbot

mkdir -p "$APP_DIR/data" "$APP_DIR/logs"
cp tradingbot-backend.jar "$APP_DIR/tradingbot-backend.jar"
cp run-backend-release.sh "$APP_DIR/run-backend-release.sh"
cp tradingbot-backend.service "$SERVICE_FILE"
chmod +x "$APP_DIR/run-backend-release.sh"
chown -R tradingbot:tradingbot /opt/tradingbot

if [ ! -f "$APP_DIR/.env" ]; then
  cp .env.example "$APP_DIR/.env"
  chown tradingbot:tradingbot "$APP_DIR/.env"
  chmod 600 "$APP_DIR/.env"
fi

systemctl daemon-reload
systemctl enable --now tradingbot-backend

echo "Installed tradingbot-backend."
echo "Status: systemctl status tradingbot-backend"
echo "Logs: journalctl -u tradingbot-backend -f"
