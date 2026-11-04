#!/usr/bin/env bash
# Trigger the deliberate fault for the on-call demo (issue #118).
#
# Serves the seeded-fault feed fixture from a throwaway web server on 127.0.0.1, subscribes to it
# on the local PodClatch Server every 15 seconds until stopped (Ctrl+C), and prints only the HTTP
# status of each request. On the branch demo/seeded-fault the server answers 500, so the 5xx
# ratio alert ("PodClatch Server 5xx ratio high") fires after roughly 6 to 8 minutes.
#
# The fixture exists only on demo/seeded-fault (server/tests/fixtures/). Run this script from a
# checkout of that branch (the server you test must be built from it too), or point ONCALL_FIXTURE
# at a copy of the file. See README "On-call agent demo".
#
# Usage (from the repository root):  bash ops/oncall/trigger-fault.sh
#
# Environment (all optional):
#   ONCALL_SERVER_URL    server base URL; the host must be localhost or 127.0.0.1
#                        (default http://127.0.0.1:${PODCLATCH_PORT:-8180})
#   ONCALL_FIXTURE       fixture path (default server/tests/fixtures/seeded_fault_fractional_duration.xml)
#   ONCALL_FIXTURE_HOST  host name the SERVER uses to reach this machine (default host.docker.internal,
#                        right for Docker Desktop; use 172.17.0.1 or similar on Linux)
#   ONCALL_FIXTURE_PORT  port of the throwaway fixture server (default 8480)
#   ONCALL_INTERVAL      seconds between requests (default 15)
#   ONCALL_MAX_REQUESTS  stop after this many requests (default 0 = until stopped)
#   ONCALL_ENV_FILE      file holding PODCLATCH_TOKEN (default .env in the repository root)
#
# The token is read from the env file and passed to curl on stdin; it is never printed, never put
# on a command line, and never written anywhere else.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ONCALL_ENV_FILE:-$ROOT/.env}"
FIXTURE="${ONCALL_FIXTURE:-$ROOT/server/tests/fixtures/seeded_fault_fractional_duration.xml}"
FIXTURE_HOST="${ONCALL_FIXTURE_HOST:-host.docker.internal}"
FIXTURE_PORT="${ONCALL_FIXTURE_PORT:-8480}"
INTERVAL="${ONCALL_INTERVAL:-15}"
MAX_REQUESTS="${ONCALL_MAX_REQUESTS:-0}"

fail() { echo "trigger-fault: $*" >&2; exit 1; }

# Read PODCLATCH_PORT from the env file only for the default URL (never the token here).
default_port=8180
if [ -f "$ENV_FILE" ]; then
  p="$(grep -E '^PODCLATCH_PORT=' "$ENV_FILE" | tail -n1 | cut -d= -f2- | tr -d '\r"' || true)"
  case "$p" in ''|*[!0-9]*) ;; *) default_port="$p" ;; esac
fi
SERVER_URL="${ONCALL_SERVER_URL:-http://127.0.0.1:${PODCLATCH_PORT:-$default_port}}"
SERVER_URL="${SERVER_URL%/}"

# Refuse anything but localhost or 127.0.0.1 (no userinfo, no other host, no path tricks).
if ! [[ "$SERVER_URL" =~ ^https?://(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]]; then
  fail "refusing to run: the server URL must be http(s)://localhost or http(s)://127.0.0.1 (optionally with a port), nothing else."
fi

[[ "$INTERVAL" =~ ^[0-9]+$ ]] || fail "ONCALL_INTERVAL must be a whole number of seconds."
[[ "$MAX_REQUESTS" =~ ^[0-9]+$ ]] || fail "ONCALL_MAX_REQUESTS must be a whole number."
[[ "$FIXTURE_PORT" =~ ^[0-9]+$ ]] || fail "ONCALL_FIXTURE_PORT must be a whole number."
[[ "$FIXTURE_HOST" =~ ^[A-Za-z0-9.-]+$ ]] || fail "ONCALL_FIXTURE_HOST must be a plain host name or address."

[ -f "$FIXTURE" ] || fail "fixture not found: $FIXTURE
It exists only on the branch demo/seeded-fault. Check that branch out (git switch demo/seeded-fault, or git worktree add ../podclatch-demo demo/seeded-fault), or set ONCALL_FIXTURE."
[ -f "$ENV_FILE" ] || fail "env file not found: $ENV_FILE (copy .env.example to .env and set PODCLATCH_TOKEN)."

TOKEN="$(grep -E '^PODCLATCH_TOKEN=' "$ENV_FILE" | tail -n1 | cut -d= -f2- | tr -d '\r' || true)"
TOKEN="${TOKEN%\"}"; TOKEN="${TOKEN#\"}"
[ -n "$TOKEN" ] || fail "PODCLATCH_TOKEN is empty or missing in $ENV_FILE."
# Only plain token characters, so the value cannot break out of the curl config line below.
[[ "$TOKEN" =~ ^[A-Za-z0-9._~+/=-]+$ ]] || fail "PODCLATCH_TOKEN has characters this script does not pass on; use letters, digits, and . _ ~ + / = -."

PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || fail "python3 is needed to serve the fixture."
command -v curl >/dev/null || fail "curl is needed."

WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  [ -z "$SERVER_PID" ] || kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Serve a directory that holds only the fixture, bound to loopback.
cp "$FIXTURE" "$WORK/seeded-fault.xml"
"$PY" -m http.server "$FIXTURE_PORT" --bind 127.0.0.1 --directory "$WORK" >/dev/null 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 50); do
  if curl -s -o /dev/null "http://127.0.0.1:$FIXTURE_PORT/seeded-fault.xml"; then break; fi
  kill -0 "$SERVER_PID" 2>/dev/null || fail "the fixture server did not start (is port $FIXTURE_PORT in use? set ONCALL_FIXTURE_PORT)."
  sleep 0.2
done

FEED_URL="http://$FIXTURE_HOST:$FIXTURE_PORT/seeded-fault.xml"
BODY="{\"feedUrl\": \"$FEED_URL\"}"

echo "Serving the fixture on 127.0.0.1:$FIXTURE_PORT; subscribing at $SERVER_URL every ${INTERVAL}s. Ctrl+C to stop."
sent=0
while :; do
  # The Authorization header goes in on stdin (curl -K -), not on the command line.
  code="$(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" |
    curl -s -o /dev/null -w '%{http_code}' -K - --max-time 30 \
      -H 'Content-Type: application/json' -X POST -d "$BODY" "$SERVER_URL/v1/feeds" || true)"
  sent=$((sent + 1))
  echo "$(date -u +%H:%M:%SZ) request $sent: HTTP ${code:-000}"
  if [ "$MAX_REQUESTS" -gt 0 ] && [ "$sent" -ge "$MAX_REQUESTS" ]; then break; fi
  sleep "$INTERVAL"
done
