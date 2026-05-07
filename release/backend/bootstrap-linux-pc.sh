#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this on the Linux PC with sudo: sudo ./scripts/bootstrap-linux-pc.sh" >&2
  exit 1
fi

WITH_FRONTEND="${WITH_FRONTEND:-0}"

echo "Installing backend runtime/build dependencies..."
apt-get update
apt-get install -y \
  bash \
  ca-certificates \
  curl \
  git \
  openjdk-17-jdk \
  sqlite3 \
  tar \
  unzip

if [ "$WITH_FRONTEND" = "1" ]; then
  echo "Installing Node.js/npm from the OS package repository for optional local frontend builds..."
  apt-get install -y nodejs npm
fi

echo "Disabling system sleep/hibernate targets for 24/7 service use..."
systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target >/dev/null 2>&1 || true

echo ""
echo "Installed versions:"
java -version
git --version
sqlite3 --version
if [ "$WITH_FRONTEND" = "1" ]; then
  node --version || true
  npm --version || true
fi

echo ""
echo "Next steps:"
echo "1. git clone or git pull the repo on this PC."
echo "2. cd into the repo."
echo "3. Build the backend release: ./scripts/build-backend-release.sh"
echo "4. Install the service: cd release/backend && sudo ./install-linux-systemd-backend.sh"
echo "5. Check it: curl http://localhost:7070/api/system/health"
