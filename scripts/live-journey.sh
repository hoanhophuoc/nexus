#!/usr/bin/env bash
#
# Runs the opt-in RealServerUserJourneyFxTest against a fully isolated stack.
#
# Requirements: Docker Engine with network access for image layers and Gradle
# dependencies. No Compose stack, JDK, display, pre-existing admin account,
# database, or Redis is needed.
#
# The runner builds the server and test-runner images (unless --skip-build),
# creates a private network, starts throwaway PostgreSQL and Redis containers,
# starts the server, registers a temporary admin through the real API and
# promotes it in the throwaway database, then runs the JavaFX test headlessly in
# a Gradle-based container with the Pango runtime JavaFX needs for text layout.
# The test task is forced to run so Gradle up-to-date results cannot skip the
# journey. On every exit path it removes the test/server/Redis/PostgreSQL
# containers, the private network, and its temporary Gradle cache.
#
# Usage:
#   ./scripts/live-journey.sh [--skip-build]
#
# Optional overrides:
#   CHAT_REAL_ADMIN_USERNAME  Admin username to provision (default: generated)
#   CHAT_REAL_ADMIN_PASSWORD  Admin password to provision (default: generated)
#   CHAT_LIVE_SERVER_IMAGE    Server image tag (default: chat-system-live-server:latest)
#   CHAT_LIVE_TEST_IMAGE      Test runner image tag (default: chat-system-live-test:latest)
#   CHAT_LIVE_GRADLE_IMAGE    Base Gradle image for the test runner
#                             (default: gradle:9.8.2-jdk27; the wrapper runs the
#                              actual Gradle version)
#   CHAT_LIVE_GRADLE_HOME     Reusable Gradle cache directory; it must not be in
#                             use by another Gradle build. A temporary one is
#                             created and removed when this is not set
#
# Exit status is nonzero on any build, container, health, provisioning or test
# failure, or on interruption. Temporary resources are removed before the runner
# exits.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RUN_ID="$(date +%s)-$$"
NETWORK="chat-system-live-$RUN_ID"
DB_CONTAINER="chat-system-live-db-$RUN_ID"
REDIS_CONTAINER="chat-system-live-redis-$RUN_ID"
SERVER_CONTAINER="chat-system-live-server-$RUN_ID"
TEST_CONTAINER="chat-system-live-test-$RUN_ID"
SERVER_IMAGE="${CHAT_LIVE_SERVER_IMAGE:-chat-system-live-server:latest}"
TEST_IMAGE="${CHAT_LIVE_TEST_IMAGE:-chat-system-live-test:latest}"
GRADLE_BASE_IMAGE="${CHAT_LIVE_GRADLE_IMAGE:-gradle:9.8.2-jdk27}"
SKIP_BUILD=0
GRADLE_HOME=""
TEMP_GRADLE_HOME=0

usage() {
    sed -n '2,/^set -Eeuo pipefail$/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

while (($#)); do
    case "$1" in
        --skip-build)
            SKIP_BUILD=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

die() {
    echo "live-journey: $*" >&2
    exit 1
}

random_hex() {
    printf '%04x%04x' "$RANDOM" "$RANDOM"
}

json_escape() {
    local value="$1"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    value="${value//$'\n'/\\n}"
    value="${value//$'\r'/\\r}"
    value="${value//$'\t'/\\t}"
    printf '%s' "$value"
}

command -v docker >/dev/null 2>&1 || die "Docker is required and was not found on PATH"
command -v id >/dev/null 2>&1 || die "the id command is required"
command -v mktemp >/dev/null 2>&1 || die "the mktemp command is required"

ADMIN_USER="${CHAT_REAL_ADMIN_USERNAME:-live_admin_$(random_hex)}"
ADMIN_PASSWORD="${CHAT_REAL_ADMIN_PASSWORD:-$(random_hex)$(random_hex)$(random_hex)$(random_hex)}"
ADMIN_EMAIL="${ADMIN_USER}@example.test"
[[ "$ADMIN_USER" =~ ^[A-Za-z0-9_.-]{3,32}$ ]] \
    || die "CHAT_REAL_ADMIN_USERNAME must be 3-32 characters from A-Z, a-z, 0-9, dot, underscore or hyphen"
((${#ADMIN_PASSWORD} >= 12 && ${#ADMIN_PASSWORD} <= 128)) \
    || die "CHAT_REAL_ADMIN_PASSWORD must be 12-128 characters"

GRADLE_HOME="${CHAT_LIVE_GRADLE_HOME:-}"
if [[ -z "$GRADLE_HOME" ]]; then
    GRADLE_HOME="$(mktemp -d "${TMPDIR:-/tmp}/chat-system-live-gradle.XXXXXX")"
    TEMP_GRADLE_HOME=1
fi
[[ -d "$GRADLE_HOME" ]] || die "CHAT_LIVE_GRADLE_HOME is not a directory: $GRADLE_HOME"

DB_PASSWORD="$(random_hex)$(random_hex)$(random_hex)$(random_hex)"
REDIS_PASSWORD="$(random_hex)$(random_hex)$(random_hex)$(random_hex)"
DB_NAME="chat_system"
DB_USER="chat"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    echo "live-journey: removing temporary stack" >&2
    for container in "$TEST_CONTAINER" "$SERVER_CONTAINER" "$REDIS_CONTAINER" "$DB_CONTAINER"; do
        docker rm -f -v "$container" >/dev/null 2>&1 || true
    done
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    if ((TEMP_GRADLE_HOME == 1)); then
        rm -rf -- "$GRADLE_HOME"
    fi
    exit "$status"
}
interrupted() {
    echo "live-journey: interrupted; cleaning up" >&2
    exit 130
}
terminated() {
    echo "live-journey: terminated; cleaning up" >&2
    exit 143
}
trap cleanup EXIT
trap interrupted INT
trap terminated TERM

printf -v ADMIN_JSON \
    '{"username":"%s","fullName":"Live Journey Admin","email":"%s","address":"1 Live Journey Way","birthDate":"1990-01-01","gender":"other","password":"%s"}' \
    "$(json_escape "$ADMIN_USER")" "$(json_escape "$ADMIN_EMAIL")" "$(json_escape "$ADMIN_PASSWORD")"

if ((SKIP_BUILD == 0)); then
    echo "live-journey: building server image $SERVER_IMAGE" >&2
    if ! docker build --tag "$SERVER_IMAGE" --file "$ROOT_DIR/Dockerfile" "$ROOT_DIR"; then
        die "server image build failed"
    fi
    echo "live-journey: building test runner image $TEST_IMAGE" >&2
    if ! docker build --tag "$TEST_IMAGE" \
        --build-arg "GRADLE_IMAGE=$GRADLE_BASE_IMAGE" \
        --file "$ROOT_DIR/scripts/live-journey-test.Dockerfile" \
        "$ROOT_DIR/scripts"; then
        die "test runner image build failed"
    fi
fi
docker image inspect "$SERVER_IMAGE" >/dev/null 2>&1 \
    || die "server image '$SERVER_IMAGE' is missing; rerun without --skip-build"
docker image inspect "$TEST_IMAGE" >/dev/null 2>&1 \
    || die "test runner image '$TEST_IMAGE' is missing; rerun without --skip-build"

if ! docker network create "$NETWORK" >/dev/null; then
    die "could not create the isolated Docker network"
fi

echo "live-journey: starting throwaway PostgreSQL" >&2
if ! docker run -d --rm \
    --name "$DB_CONTAINER" \
    --network "$NETWORK" \
    -e POSTGRES_DB="$DB_NAME" \
    -e POSTGRES_USER="$DB_USER" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    postgres:18-alpine \
    >/dev/null; then
    die "could not start the throwaway PostgreSQL container"
fi

db_ready=0
for ((attempt = 1; attempt <= 60; attempt++)); do
    if docker exec "$DB_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
        db_ready=1
        break
    fi
    sleep 1
done
if ((db_ready == 0)); then
    docker logs --tail 120 "$DB_CONTAINER" >&2 || true
    die "throwaway PostgreSQL did not become ready"
fi

echo "live-journey: starting throwaway Redis" >&2
if ! docker run -d --rm \
    --name "$REDIS_CONTAINER" \
    --network "$NETWORK" \
    -e CHAT_REDIS_PASSWORD="$REDIS_PASSWORD" \
    redis:8.10-alpine \
    sh -c 'exec redis-server --save "" --appendonly no --requirepass "$CHAT_REDIS_PASSWORD"' \
    >/dev/null; then
    die "could not start the throwaway Redis container"
fi

redis_ready=0
for ((attempt = 1; attempt <= 30; attempt++)); do
    if docker exec -e REDISCLI_AUTH="$REDIS_PASSWORD" "$REDIS_CONTAINER" \
        redis-cli ping 2>/dev/null | grep -qx PONG; then
        redis_ready=1
        break
    fi
    sleep 1
done
if ((redis_ready == 0)); then
    docker logs --tail 80 "$REDIS_CONTAINER" >&2 || true
    die "throwaway Redis did not become ready"
fi

echo "live-journey: starting isolated server" >&2
if ! docker run -d --rm \
    --name "$SERVER_CONTAINER" \
    --network "$NETWORK" \
    -e CHAT_DB_URL="jdbc:postgresql://$DB_CONTAINER:5432/$DB_NAME" \
    -e CHAT_DB_USER="$DB_USER" \
    -e CHAT_DB_PASSWORD="$DB_PASSWORD" \
    -e CHAT_REDIS_HOST="$REDIS_CONTAINER" \
    -e CHAT_REDIS_PORT=6379 \
    -e CHAT_REDIS_PASSWORD="$REDIS_PASSWORD" \
    -e CHAT_EMAIL_AUTH=false \
    -e CHAT_EMAIL_FROM=no-reply@localhost \
    -e CHAT_MAIL_HEALTH_ENABLED=false \
    -e CHAT_SECURE_COOKIE=false \
    -e CHAT_SERVER_HOST=0.0.0.0 \
    -e CHAT_SERVER_PORT=8080 \
    "$SERVER_IMAGE" \
    >/dev/null; then
    die "could not start the isolated server container"
fi

server_ready=0
for ((attempt = 1; attempt <= 60; attempt++)); do
    if ! docker inspect -f '{{.State.Running}}' "$SERVER_CONTAINER" 2>/dev/null | grep -qx true; then
        docker logs --tail 160 "$SERVER_CONTAINER" >&2 || true
        die "isolated server exited before becoming healthy"
    fi
    if docker exec "$SERVER_CONTAINER" \
        curl --fail --silent --output /dev/null http://127.0.0.1:8080/actuator/health; then
        server_ready=1
        break
    fi
    sleep 1
done
if ((server_ready == 0)); then
    docker logs --tail 160 "$SERVER_CONTAINER" >&2 || true
    die "isolated server did not become healthy"
fi

echo "live-journey: provisioning a temporary admin account" >&2
if ! docker exec "$SERVER_CONTAINER" sh -c \
    'curl --fail --silent --show-error -c /tmp/live-admin-cookies http://127.0.0.1:8080/api/v1/auth/csrf -o /tmp/live-csrf.json'; then
    die "could not fetch a CSRF token from the isolated server"
fi
CSRF_TOKEN="$(docker exec "$SERVER_CONTAINER" \
    awk '$6 == "XSRF-TOKEN" { print $7 }' /tmp/live-admin-cookies)"
[[ -n "$CSRF_TOKEN" ]] || die "the isolated server did not set an XSRF-TOKEN cookie"

if ! REGISTER_STATUS="$(printf '%s' "$ADMIN_JSON" | docker exec -i "$SERVER_CONTAINER" sh -c '
    curl --silent --show-error \
        -b /tmp/live-admin-cookies -c /tmp/live-admin-cookies \
        -H "Content-Type: application/json" \
        -H "X-XSRF-TOKEN: $1" \
        --data-binary @- \
        -o /tmp/live-admin-register.json \
        -w "%{http_code}" \
        http://127.0.0.1:8080/api/v1/auth/register
' sh "$CSRF_TOKEN")"; then
    die "could not register the temporary admin account"
fi
if [[ "$REGISTER_STATUS" != "201" ]]; then
    docker exec "$SERVER_CONTAINER" cat /tmp/live-admin-register.json >&2 || true
    die "temporary admin registration failed with HTTP $REGISTER_STATUS"
fi

PROMOTED="$(docker exec "$DB_CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -qAtc \
    "UPDATE chat_v1.user_account SET role = 'admin' WHERE username_normalized = lower('$ADMIN_USER') RETURNING username;")" \
    || die "could not promote the temporary admin in the throwaway database"
[[ "$PROMOTED" == "$ADMIN_USER" ]] \
    || die "temporary admin promotion did not find the registered account"

echo "live-journey: running the JavaFX journey headlessly" >&2
if ! docker run --rm \
    --name "$TEST_CONTAINER" \
    --network "$NETWORK" \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e GRADLE_USER_HOME=/gradle-home \
    -e JAVA_TOOL_OPTIONS='-Dglass.platform=headless -Dprism.order=sw -Djava.awt.headless=true -Djavafx.cachedir=/tmp/javafx-cache' \
    -e CHAT_REAL_SERVER=true \
    -e CHAT_REAL_SERVER_ORIGIN="http://$SERVER_CONTAINER:8080" \
    -e CHAT_REAL_ADMIN_USERNAME="$ADMIN_USER" \
    -e CHAT_REAL_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    -v "$ROOT_DIR:/workspace" \
    -v "$GRADLE_HOME:/gradle-home" \
    -w /workspace \
    "$TEST_IMAGE" \
    gradle --no-daemon --console=plain --rerun-tasks \
    :desktop-user:fxTest --tests '*RealServerUserJourneyFxTest*'; then
    die "the live journey failed; see the Gradle output above"
fi

echo "live-journey: passed" >&2
