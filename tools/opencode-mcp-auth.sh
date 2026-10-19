#!/bin/sh
# Run `opencode mcp auth <server>` and open the authorization URL
# automatically, so you never have to copy/paste the pairing link.
#
# Usage:  sh tools/opencode-mcp-auth.sh [server-name]
#
# Opening order:
#   1. Android intent targeted at the Eruda app when adb is available
#      (lands directly on the native approval dialog, no browser tab)
#   2. termux-open-url (Termux:API)
#   3. xdg-open (desktop)
#   4. print the URL
set -u

server="${1:-ErudaMCP}"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT

echo "Running: opencode mcp auth $server"
opencode mcp auth "$server" >"$out" 2>&1 &
pid=$!

opened=""
while kill -0 "$pid" 2>/dev/null; do
  url="$(grep -aoE 'http://127\.0\.0\.1:[0-9]+/oauth/authorize[^[:space:]]*' "$out" | head -n1)"
  if [ -n "$url" ] && [ "$url" != "$opened" ]; then
    opened="$url"
    echo "Authorization URL: $url"
    if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
      echo "Opening in the Eruda app (native approval dialog)…"
      adb shell am start -a android.intent.action.VIEW -d "'$url'" -p io.liriliri.eruda >/dev/null 2>&1 \
        || echo "adb intent failed; open the URL manually."
    elif command -v termux-open-url >/dev/null 2>&1; then
      echo "Opening with termux-open-url…"
      termux-open-url "$url"
    elif command -v xdg-open >/dev/null 2>&1; then
      echo "Opening with xdg-open…"
      xdg-open "$url" >/dev/null 2>&1 || echo "xdg-open failed; open the URL manually."
    else
      echo "Open this URL manually: $url"
    fi
  fi
  sleep 1
done

wait "$pid"
status=$?
sed -n '$p' "$out" | tr -d '\r'
exit "$status"
