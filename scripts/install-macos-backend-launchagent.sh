#!/usr/bin/env bash
set -euo pipefail

LABEL="com.tradingbot.backend"
PROJECT_ROOT="/Users/anishpatel/Documents/SoftwareProject/trading_bot"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
BACKEND_DIR="$PROJECT_ROOT/backend"
RUN_SCRIPT="$PROJECT_ROOT/scripts/run-backend.sh"

mkdir -p "$HOME/Library/LaunchAgents" "$BACKEND_DIR/logs"

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>

  <key>ProgramArguments</key>
  <array>
    <string>$RUN_SCRIPT</string>
  </array>

  <key>WorkingDirectory</key>
  <string>$BACKEND_DIR</string>

  <key>RunAtLoad</key>
  <true/>

  <key>KeepAlive</key>
  <true/>

  <key>StandardOutPath</key>
  <string>$BACKEND_DIR/logs/launchd.out.log</string>

  <key>StandardErrorPath</key>
  <string>$BACKEND_DIR/logs/launchd.err.log</string>

  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
</dict>
</plist>
PLIST

chmod +x "$RUN_SCRIPT"

launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart -k "gui/$(id -u)/$LABEL"

echo "Installed and started $LABEL"
echo "Status: launchctl print gui/$(id -u)/$LABEL"
echo "Logs: $BACKEND_DIR/logs/launchd.out.log and $BACKEND_DIR/logs/launchd.err.log"
