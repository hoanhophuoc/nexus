#!/usr/bin/env bash
# Exercises scripts/check-database-identity.sh against a throwaway PostgreSQL, including the two
# cases that must never be mistaken for a fresh install: a rejected password and an unreachable host.
#
#   ./scripts/test-database-identity.sh
#
# Requires Docker. Starts its own container on a loopback port, removes it on every exit path.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PORT="${NEXUS_TEST_DB_PORT:-15434}"
CONTAINER="nexus-identity-test-$$"
IMAGE="postgres:18-alpine"
CHECK="./scripts/check-database-identity.sh"
failures=0

# shellcheck disable=SC2329 # invoked through the trap below
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

docker run -d --rm --name "$CONTAINER" \
  -e POSTGRES_DB=nexus -e POSTGRES_USER=nexus -e POSTGRES_PASSWORD=nexus \
  -p "127.0.0.1:$PORT:5432" "$IMAGE" >/dev/null \
  || { echo "cannot start the pinned $IMAGE image" >&2; exit 2; }
for _ in $(seq 1 60); do
  docker exec "$CONTAINER" pg_isready -U nexus -d nexus >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$CONTAINER" pg_isready -U nexus -d nexus >/dev/null 2>&1 \
  || { echo "the throwaway database did not become ready" >&2; exit 2; }

psql_() { docker exec -i "$CONTAINER" psql -U nexus -d "$1" -q -v ON_ERROR_STOP=1 -c "$2"; }

expect() { # $1 = expected exit, $2 = label, rest = extra environment for the check
  local want="$1" label="$2" output status
  shift 2
  output="$(env "$@" "$CHECK" 2>&1)"
  status=$?
  if [ "$status" = "$want" ]; then
    printf 'ok   %-58s exit %s\n' "$label" "$status"
  else
    printf 'FAIL %-58s exit %s, wanted %s\n%s\n' "$label" "$status" "$want" "$output"
    failures=$((failures + 1))
  fi
}

expect_connection_error() { # $1 = label, rest = environment that breaks the connection
  local label="$1" output status
  shift
  output="$(env "$@" "$CHECK" 2>&1)"
  status=$?
  if [ "$status" = "2" ] && [[ "$output" != *"fresh installation"* ]] && [[ "$output" == *"cannot query"* ]]; then
    printf 'ok   %-58s exit 2\n' "$label"
  else
    printf 'FAIL %-58s exit %s, wanted 2 without a fresh-install claim\n%s\n' "$label" "$status" "$output"
    failures=$((failures + 1))
  fi
}

export NEXUS_DB_URL="jdbc:postgresql://127.0.0.1:$PORT/nexus"
export NEXUS_DB_USER=nexus
export NEXUS_DB_PASSWORD=nexus

expect 0 "empty database is a fresh install"

psql_ nexus "CREATE SCHEMA chat_v1; CREATE TABLE chat_v1.user_account (id int);" >/dev/null
expect 1 "pre-rename schema holding the data blocks the start"

psql_ nexus "ALTER SCHEMA chat_v1 RENAME TO nexus;
             CREATE TABLE nexus.flyway_schema_history (installed_rank int);" >/dev/null
expect 0 "renamed schema is accepted"

psql_ postgres "CREATE DATABASE chat_system;" >/dev/null
psql_ nexus "DROP SCHEMA nexus CASCADE;" >/dev/null
expect 1 "pre-rename database blocks the start"

expect_connection_error "rejected password is not a fresh install" NEXUS_DB_PASSWORD=wrong-password
expect_connection_error "no postgres on that port is not a fresh install" \
  NEXUS_DB_URL="jdbc:postgresql://127.0.0.1:1/nexus"

if [ "$failures" -eq 0 ]; then
  echo "database-identity test: all branches behaved as documented"
  exit 0
fi
echo "database-identity test: $failures branch(es) failed" >&2
exit 1
