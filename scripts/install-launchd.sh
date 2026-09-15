#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
LABEL=com.vinted.bot
USER_ID=$(id -u)
PLIST_DIR="$HOME/Library/LaunchAgents"
PLIST="$PLIST_DIR/$LABEL.plist"

[[ -f "$ROOT/.env" ]] || { echo ".env manquant"; exit 1; }
mkdir -p "$PLIST_DIR" "$ROOT/logs"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>$LABEL</string>
<key>ProgramArguments</key><array><string>/bin/bash</string><string>$ROOT/deploy/run-local.sh</string></array>
<key>WorkingDirectory</key><string>$ROOT</string>
<key>StandardOutPath</key><string>$ROOT/logs/launchd.out.log</string>
<key>StandardErrorPath</key><string>$ROOT/logs/launchd.err.log</string>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
</dict></plist>
EOF

plutil -lint "$PLIST" >/dev/null
launchctl bootout "gui/$USER_ID" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$USER_ID" "$PLIST"
launchctl kickstart -k "gui/$USER_ID/$LABEL"
echo "Service installé : $LABEL"
