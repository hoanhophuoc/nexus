#!/usr/bin/env bash
# Fails when the database still carries the pre-rename identity, because the server would apply its
# migrations to an empty `nexus` schema and the installation would look as if every row was lost.
#
# Run this before starting a server against a database created before the rename:
#
#   ./scripts/check-database-identity.sh
#
# Connection comes from the server's own variables (NEXUS_DB_URL, NEXUS_DB_USER,
# NEXUS_DB_PASSWORD). psql is used when it is installed; otherwise the pinned postgres image runs
# it through `docker run --network host`, so loopback hosts work without a host client.
#
# Exit status: 0 the schema is current, 1 a rename is required, 2 the check could not run.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

URL="${NEXUS_DB_URL:-jdbc:postgresql://127.0.0.1:5432/nexus}"
if [[ ! "$URL" =~ ^jdbc:postgresql://([^:/?]+)(:([0-9]+))?/([^?]+) ]]; then
  echo "Cannot read a host, port and database from NEXUS_DB_URL: $URL" >&2
  exit 2
fi
HOST="${BASH_REMATCH[1]}"
PORT="${BASH_REMATCH[3]:-5432}"
DATABASE="${BASH_REMATCH[4]}"
USER="${NEXUS_DB_USER:-nexus}"
PASSWORD="${NEXUS_DB_PASSWORD:-nexus}"

sql() { # $1 = database, $2 = statement -> prints rows, fails on connection or SQL error
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$PASSWORD" psql -h "$HOST" -p "$PORT" -U "$USER" -d "$1" \
      -tAX -v ON_ERROR_STOP=1 -c "$2"
  else
    docker run --rm --network host -e PGPASSWORD="$PASSWORD" postgres:18-alpine \
      psql -h "$HOST" -p "$PORT" -U "$USER" -d "$1" -tAX -v ON_ERROR_STOP=1 -c "$2"
  fi
}

tables_in() { # $1 = database, $2 = schema
  sql "$1" "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$2'"
}

fail() {
  echo "database-identity: $1" >&2
  shift
  for line in "$@"; do echo "  $line" >&2; done
  exit 1
}

echo "database-identity: checking $USER@$HOST:$PORT/$DATABASE"

if ! NEXUS_TABLES="$(tables_in "$DATABASE" nexus 2>/dev/null)"; then
  if LEGACY_DB="$(sql postgres "SELECT count(*) FROM pg_database WHERE datname = 'chat_system'" 2>/dev/null)" \
      && [ "${LEGACY_DB:-0}" != "0" ]; then
    fail "database '$DATABASE' does not exist while the pre-rename database 'chat_system' does." \
      "Renaming the server now would create an empty '$DATABASE' and hide every row." \
      "Run: ALTER DATABASE chat_system RENAME TO nexus;" \
      "Then point NEXUS_DB_URL at it and run this check again."
  fi
  echo "database-identity: database '$DATABASE' does not exist yet; a fresh install will create it."
  exit 0
fi

NEXUS_TABLES="${NEXUS_TABLES:-0}"
if [ "$NEXUS_TABLES" != "0" ]; then
  HISTORY="$(sql "$DATABASE" "SELECT count(*) FROM information_schema.tables
    WHERE table_schema = 'nexus' AND table_name = 'flyway_schema_history'" 2>/dev/null || echo 0)"
  echo "database-identity: schema 'nexus' is in use ($NEXUS_TABLES tables, flyway history: $HISTORY)."
  LEGACY_TABLES="$(tables_in "$DATABASE" chat_v1 2>/dev/null || echo 0)"
  LEGACY_DB="$(sql postgres "SELECT count(*) FROM pg_database WHERE datname = 'chat_system'" 2>/dev/null || echo 0)"
  if [ "${LEGACY_TABLES:-0}" != "0" ] || [ "${LEGACY_DB:-0}" != "0" ]; then
    echo "database-identity: warning: the pre-rename schema or database still exists next to the" \
      "current one; the server uses 'nexus' only, so the old rows stay ignored until removed." >&2
  fi
  exit 0
fi

LEGACY_TABLES="$(tables_in "$DATABASE" chat_v1 2>/dev/null || echo 0)"
LEGACY_DB="$(sql postgres "SELECT count(*) FROM pg_database WHERE datname = 'chat_system'" 2>/dev/null || echo 0)"

if [ "${LEGACY_TABLES:-0}" != "0" ]; then
  fail "schema 'chat_v1' holds $LEGACY_TABLES tables while 'nexus' is empty." \
    "Starting the server now would migrate an empty 'nexus' schema and the data would look lost." \
    "Run: ALTER SCHEMA chat_v1 RENAME TO nexus;  (Flyway's history table moves with it)" \
    "Then run this check again and start the server."
fi

if [ "${LEGACY_DB:-0}" != "0" ]; then
  fail "database 'chat_system' exists while '$DATABASE' has an empty 'nexus' schema." \
    "Run: ALTER DATABASE chat_system RENAME TO nexus;" \
    "Then run this check again and start the server."
fi

echo "database-identity: schema 'nexus' is empty and no pre-rename schema or database was found;"
echo "database-identity: this looks like a fresh installation."
exit 0
