#!/usr/bin/env bash
set -euo pipefail

cat <<'NOTE'
This config helps keep the Mac awake while it is plugged into power.

Important:
- It cannot make a powered-off Mac run the backend.
- It may not bypass MacBook closed-lid sleep unless the Mac is in supported clamshell mode
  with power, external display, and external keyboard/mouse.
- For true 24/7 reliability, keep the lid open or use a Mac mini / mini PC on a UPS.

The script will now apply AC-power settings with sudo.
NOTE

sudo pmset -c sleep 0
sudo pmset -c disksleep 0
sudo pmset -c displaysleep 10
sudo pmset -c powernap 1
sudo pmset -c tcpkeepalive 1
sudo pmset -c womp 1
sudo pmset -c autorestart 1 || true

echo "Applied plugged-in power settings:"
pmset -g custom
