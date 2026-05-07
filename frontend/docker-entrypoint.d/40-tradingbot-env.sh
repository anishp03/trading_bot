#!/bin/sh
set -eu

api_base_url="${TRADINGBOT_API_BASE_URL:-http://localhost:7070}"
escaped_api_base_url="$(printf '%s' "$api_base_url" | sed 's/\\/\\\\/g; s/"/\\"/g')"

cat > /usr/share/nginx/html/env-config.js <<EOF
window.__TRADINGBOT_CONFIG__ = {
  API_BASE_URL: "$escaped_api_base_url"
};
EOF
