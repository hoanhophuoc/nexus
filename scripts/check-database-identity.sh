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
# Exit status: 0 the schema is current or this is a fresh install, 1 a rename is required,
# 2 the check could not run: unreachable server, rejected credentials, or no usable client.
# A failed connection is never reported as a fresh install.
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
MAINTENANCE="${NEXUS_DB_MAINTENANCE:-postgres}"
SQL_ERROR=""

query() { # $1 = database, $2 = statement; prints rows, or returns 1 with SQL_ERROR filled in
  local output status
  if command -v psql >/dev/null 2>&1; then
    output="$(PGPASSWORD="$PASSWORD" psql -h "$HOST" -p "$PORT" -U "$USER" -d "$1" \
      -tAX -v ON_ERROR_STOP=1 -c "$2" 2>&1)"
  else
    output="$(docker run --rm --network host -e PGPASSWORD="$PASSWORD" postgres:18-alpine \
      psql -h "$HOST" -p "$PORT" -U "$USER" -d "$1" -tAX -v ON_ERROR_STOP=1 -c "$2" 2>&1)"
  fi
  status=$?
  if [ "$status" -ne 0 ]; then
    SQL_ERROR="$(printf '%s' "$output" | tr '\n\t' '  ' | sed -e 's/  */ /g' -e 's/^ //' -e 's/ *$//')"
    return 1
  fi
  printf '%s\n' "$output"
}

tables_in() { # $1 = database, $2 = schema
  query "$1" "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$2'"
}

unreachable() { # $1 = what was being read; never hides a connection problem as an empty database
  {
    echo "database-identity: cannot query $USER@$HOST:$PORT: $1."
    [ -n "$SQL_ERROR" ] && echo "  server said: $SQL_ERROR"
    echo "  Nothing was changed. Check NEXUS_DB_URL, NEXUS_DB_USER and NEXUS_DB_PASSWORD."
  } >&2
  exit 2
}

fail() {
  echo "database-identity: $1" >&2
  shift
  for line in "$@"; do echo "  $line" >&2; done
  exit 1
}

echo "database-identity: checking $USER@$HOST:$PORT/$DATABASE"

query "$MAINTENANCE" "SELECT 1" >/dev/null \
  || unreachable "the maintenance database '$MAINTENANCE' is not reachable"

DATABASE_EXISTS="$(query "$MAINTENANCE" \
  "SELECT count(*) FROM pg_database WHERE datname = '$DATABASE'")" \
  || unreachable "the database catalogue could not be read"
LEGACY_DATABASE="$(query "$MAINTENANCE" \
  "SELECT count(*) FROM pg_database WHERE datname = 'chat_system'")" \
  || unreachable "the database catalogue could not be read"

if [ "$DATABASE_EXISTS" = "0" ]; then
  if [ "$LEGACY_DATABASE" != "0" ]; then
    fail "database '$DATABASE' does not exist while the pre-rename database 'chat_system' does." \
      "Renaming the server now would create an empty '$DATABASE' and hide every row." \
      "Run: ALTER DATABASE chat_system RENAME TO nexus;" \
      "Then point NEXUS_DB_URL at it and run this check again."
  fi
  echo "database-identity: database '$DATABASE' does not exist yet; a fresh install will create it."
  exit 0
fi

NEXUS_TABLES="$(tables_in "$DATABASE" nexus)" \
  || unreachable "schema 'nexus' could not be read from database '$DATABASE'"
LEGACY_TABLES="$(tables_in "$DATABASE" chat_v1)" \
  || unreachable "schema 'chat_v1' could not be read from database '$DATABASE'"

if [ "$NEXUS_TABLES" != "0" ]; then
  HISTORY="$(query "$DATABASE" "SELECT count(*) FROM information_schema.tables
    WHERE table_schema = 'nexus' AND table_name = 'flyway_schema_history'")" \
    || unreachable "the migration history could not be read from database '$DATABASE'"
  echo "database-identity: schema 'nexus' is in use ($NEXUS_TABLES tables, flyway history: $HISTORY)."
  if [ "$LEGACY_TABLES" != "0" ] || [ "$LEGACY_DATABASE" != "0" ]; then
    echo "database-identity: warning: the pre-rename schema or database still exists next to the" \
      "current one; the server reads 'nexus' only, so the old rows stay ignored until removed." >&2
  fi
  exit 0
fi

if [ "$LEGACY_TABLES" != "0" ]; then
  fail "schema 'chat_v1' holds $LEGACY_TABLES tables while 'nexus' is empty." \
    "Starting the server now would migrate an empty 'nexus' schema and the data would look lost." \
    "Run: ALTER SCHEMA chat_v1 RENAME TO nexus;  (Flyway's history table moves with it)" \
    "Then run this check again and start the server."
fi

if [ "$LEGACY_DATABASE" != "0" ]; then
  fail "database 'chat_system' exists while '$DATABASE' has an empty 'nexus' schema." \
    "Renaming the server now would point it at an empty schema; the rows stay in 'chat_system'." \
    "Run: ALTER DATABASE chat_system RENAME TO nexus; then rename its schema to 'nexus' as well." \
    "Then run this check again and start the server."
fi

echo "database-identity: schema 'nexus' is empty and no pre-rename schema or database was found;"
echo "database-identity: this looks like a fresh installation."
exit 0
