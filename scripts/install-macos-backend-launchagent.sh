#!/usr/bin/env bash
set -euo pipefail

LABEL="com.tradingbot.backend"
SOFTWARE_ROOT="/Users/anishpatel/Documents/SoftwareProject"
PROJECT_ROOT="$SOFTWARE_ROOT/trading_bot"
LIVE_BACKEND_DIR="$SOFTWARE_ROOT/live_backend"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
RUN_SCRIPT="$LIVE_BACKEND_DIR/bin/run-live-backend.sh"
UPDATE_SCRIPT="$PROJECT_ROOT/scripts/update-live-backend.sh"

mkdir -p "$HOME/Library/LaunchAgents" "$LIVE_BACKEND_DIR/bin" "$LIVE_BACKEND_DIR/data" "$LIVE_BACKEND_DIR/logs"

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
    <string>/bin/bash</string>
    <string>$RUN_SCRIPT</string>
  </array>

  <key>WorkingDirectory</key>
  <string>$LIVE_BACKEND_DIR/backend</string>

  <key>RunAtLoad</key>
  <true/>

  <key>KeepAlive</key>
  <true/>

  <key>AbandonProcessGroup</key>
  <false/>

  <key>StandardOutPath</key>
  <string>$LIVE_BACKEND_DIR/logs/launchd.out.log</string>

  <key>StandardErrorPath</key>
  <string>$LIVE_BACKEND_DIR/logs/launchd.err.log</string>

  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
</dict>
</plist>
PLIST

chmod +x "$UPDATE_SCRIPT"
"$UPDATE_SCRIPT"

echo "Installed and started $LABEL from $LIVE_BACKEND_DIR"
echo "Status: launchctl print gui/$(id -u)/$LABEL"
echo "Logs: $LIVE_BACKEND_DIR/logs/launchd.out.log and $LIVE_BACKEND_DIR/logs/launchd.err.log"
