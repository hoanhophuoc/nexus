#!/usr/bin/env bash
#
# Runs the user client's opt-in smoke test against a server started from this checkout.
#
# It starts throwaway PostgreSQL and Redis containers on ephemeral host ports, so
# it never collides with a database or service already running on this machine,
# then runs `:server:bootRun` on a free port and waits for `/actuator/health` to
# report UP. Finally it runs `RealServerUserSmokeFxTest` under Xvfb: that test
# registers its own unique account through the real API, signs in through the
# same code path the client uses, renders the FXML shell and switches it between
# LIGHT and DARK. A green run means the SDK, the session cookie, the encrypted
# cache and the shell all work against a live service.
#
# Requirements: Docker, JDK 27 and Xvfb (or set NEXUS_SMOKE_DISPLAY_PREFIX to
# skip `xvfb-run` and use the current display).
#
# Usage:
#   ./scripts/smoke-desktop.sh
#
# Optional overrides:
#   NEXUS_SMOKE_PORT=                Server port (default: a free one is chosen)
#   NEXUS_SMOKE_KEEP=true            Leave the server and containers running
#   NEXUS_SMOKE_NO_XVFB=true         Use the current display instead of xvfb-run
#
# Everything it creates is removed on exit, including on failure or Ctrl-C.
# Logs: build/smoke/server.log
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RUN_ID="$(date +%s)-$$"
DB_CONTAINER="nexus-smoke-db-$RUN_ID"
REDIS_CONTAINER="nexus-smoke-redis-$RUN_ID"
LOG_DIR="$ROOT_DIR/build/smoke"
SERVER_LOG="$LOG_DIR/server.log"
KEEP="${NEXUS_SMOKE_KEEP:-false}"
DISPLAY_CMD=(xvfb-run -a -s "-screen 0 1280x1024x24")
if [[ "${NEXUS_SMOKE_NO_XVFB:-false}" == "true" ]]; then
    DISPLAY_CMD=()
fi

SERVER_PID=""
DB_PORT=""
REDIS_PORT=""

free_port() {
    python3 - <<'PY'
import socket
sock = socket.socket()
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
}

cleanup() {
    local status=$?
    if [[ "$KEEP" == "true" ]]; then
        echo "== leaving the server and containers running (NEXUS_SMOKE_KEEP=true)"
        echo "   server:  http://127.0.0.1:${PORT:-?}  (log: $SERVER_LOG)"
        echo "   db:      $DB_CONTAINER on 127.0.0.1:${DB_PORT:-?}"
        echo "   redis:   $REDIS_CONTAINER on 127.0.0.1:${REDIS_PORT:-?}"
        exit "$status"
    fi
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "== stopping the server"
        # setsid makes the wrapper its own process group, so this takes the Gradle
        # daemon and the forked server JVM with it; a bare kill would orphan them.
        kill -TERM -- "-$SERVER_PID" 2>/dev/null || kill -TERM "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    echo "== removing the throwaway containers"
    docker rm -f "$DB_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT INT TERM

mkdir -p "$LOG_DIR"
PORT="${NEXUS_SMOKE_PORT:-$(free_port)}"
ORIGIN="http://127.0.0.1:$PORT"

echo "== starting throwaway PostgreSQL and Redis"
docker run -d --rm --name "$DB_CONTAINER" \
    -e POSTGRES_DB=nexus -e POSTGRES_USER=nexus -e POSTGRES_PASSWORD=nexus \
    -p 127.0.0.1::5432 postgres:18-alpine >/dev/null
# The Redis password is generated per run rather than written here: the container is
# throwaway, but a literal password in the script is a secret-scanner finding.
REDIS_PASSWORD="$(od -An -tx1 -N24 /dev/urandom | tr -d ' \n')"

docker run -d --rm --name "$REDIS_CONTAINER" \
    -p 127.0.0.1::6379 \
    redis:8.10-alpine redis-server --requirepass "$REDIS_PASSWORD" --appendonly no >/dev/null
DB_PORT="$(docker port "$DB_CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
REDIS_PORT="$(docker port "$REDIS_CONTAINER" 6379/tcp | head -1 | sed 's/.*://')"

echo "== waiting for them to accept connections"
for _ in $(seq 1 60); do
    if docker exec "$DB_CONTAINER" pg_isready -U nexus -d nexus >/dev/null 2>&1 \
        && docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" ping 2>/dev/null | grep -q PONG; then
        break
    fi
    sleep 1
done
docker exec "$DB_CONTAINER" pg_isready -U nexus -d nexus >/dev/null
echo "   db on 127.0.0.1:$DB_PORT, redis on 127.0.0.1:$REDIS_PORT"

echo "== starting the server on $ORIGIN (log: $SERVER_LOG)"
NEXUS_SECURE_COOKIE=false \
NEXUS_DB_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/nexus" \
NEXUS_DB_USER=nexus \
NEXUS_DB_PASSWORD=nexus \
NEXUS_REDIS_HOST=127.0.0.1 \
NEXUS_REDIS_PORT="$REDIS_PORT" \
NEXUS_REDIS_PASSWORD="$REDIS_PASSWORD" \
NEXUS_SERVER_PORT="$PORT" \
NEXUS_MAIL_HEALTH_ENABLED=false \
setsid ./gradlew --no-daemon :server:bootRun >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

echo "== waiting for $ORIGIN/actuator/health"
ready=0
for _ in $(seq 1 150); do
    if curl --fail --silent --max-time 2 "$ORIGIN/actuator/health" | grep -q '"status":"UP"'; then
        ready=1
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "the server exited before it was ready; last lines of $SERVER_LOG:" >&2
        tail -40 "$SERVER_LOG" >&2
        exit 1
    fi
    sleep 2
done
if [[ "$ready" != 1 ]]; then
    echo "the server did not report UP within five minutes; last lines of $SERVER_LOG:" >&2
    tail -40 "$SERVER_LOG" >&2
    exit 1
fi
echo "== server is up"

echo "== running the client smoke test"
export NEXUS_REAL_SERVER=true
export NEXUS_REAL_SERVER_ORIGIN="$ORIGIN"
"${DISPLAY_CMD[@]}" ./gradlew --no-daemon :desktop-user:fxTest \
    --tests '*RealServerUserSmokeFxTest' --rerun-tasks

echo "== smoke passed"
