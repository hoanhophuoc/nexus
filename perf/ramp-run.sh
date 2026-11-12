#!/usr/bin/env bash
# Runs the staged ramp against the isolated perf stack and dumps Prometheus ranges for analysis.
set -u
cd /home/phuochoan/Projects/nexus || exit 1
mkdir -p build/perf
PLAN="${PLAN:-10000:90:120,14000:60:90,18000:60:90,22000:60:90}"
USERS="${USERS:-22000}"
RATE="${RATE:-100}"
COMPOSE="docker compose -f docker-compose.perf.yml"

echo "=== build and start the perf stack ==="
$COMPOSE up -d --build db redis server prometheus > /tmp/ramp-up.log 2>&1
echo "up_exit=$?"

for i in $(seq 1 80); do
  cid=$($COMPOSE ps -q server 2>/dev/null)
  st=$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo unknown)
  [ "$st" = "healthy" ] && break
  sleep 3
done
echo "server_health=$st"
[ "$st" = "healthy" ] || { echo "SERVER_NOT_HEALTHY"; tail -20 /tmp/ramp-up.log; touch /tmp/ramp-done; exit 1; }

# Record what the run was actually configured with: the ceiling is a property of these numbers.
{
  echo "max_connections=${PERF_MAX_CONNECTIONS:-20000} accept=${PERF_ACCEPT_COUNT:-500} mem=${PERF_SERVER_MEM:-3g}"
  echo "plan=$PLAN users=$USERS rate=$RATE"
  $COMPOSE exec -T server sh -c 'echo fd_limit=$(ulimit -n)' 2>/dev/null
} > /tmp/ramp-meta.txt
cat /tmp/ramp-meta.txt

echo "=== seed $USERS users ==="
$COMPOSE exec -T db psql -U chat -d nexus -v "users=$USERS" -f - < perf/seed.sql > /tmp/ramp-seed.log 2>&1
echo "seed_exit=$?"
tail -3 /tmp/ramp-seed.log

START=$(date +%s%3N)
echo "$START" > /tmp/ramp-start-ms
echo "RUN_START_MS=$START PLAN=$PLAN USERS=$USERS RATE=$RATE"

echo "=== k6 ramp ==="
$COMPOSE --profile load run --rm \
  -e PLAN="$PLAN" -e RUN_START_MS="$START" -e USERS="$USERS" -e RATE="$RATE" \
  k6 run /perf/k6/ramp.js > /tmp/ramp-k6.log 2>&1
echo "k6_exit=$?" >> /tmp/ramp-k6.log
END=$(date +%s%3N)
echo "$END" > /tmp/ramp-end-ms
echo "k6 done, window $START..$END"

echo "=== stop k6 traffic, keep the stack for inspection ==="
$COMPOSE stop server > /dev/null 2>&1 || true
echo "=== docker stats snapshot ==="
docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.CPUPerc}}' > /tmp/ramp-stats.txt 2>&1 || true

echo "=== dump prometheus ranges ==="
python3 - "$START" "$END" <<'PY' > /tmp/ramp-prom.json
import json, sys, urllib.parse, urllib.request
start, end = int(sys.argv[1]) // 1000, int(sys.argv[2]) // 1000
metrics = [
    "process_resident_memory_bytes",
    "jvm_memory_used_bytes{area=\"heap\"}",
    "jvm_gc_pause_seconds_count",
    "jvm_gc_pause_seconds_sum",
    "jvm_threads_live_threads",
    "chat_ws_sessions",
    "hikaricp_connections_active",
    "hikaricp_connections_pending",
    "chat_outbox_oldest_age_seconds",
    "chat_outbox_batch_rows",
    "http_server_requests_seconds_count",
]
out = {}
for metric in metrics:
    query = urllib.parse.urlencode({"query": metric, "start": start, "end": end, "step": "5"})
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:19090/api/v1/query_range?{query}", timeout=30) as response:
            out[metric] = json.load(response)
    except Exception as error:  # noqa: BLE001 - report, do not fail the run
        out[metric] = {"error": str(error)}
print(json.dumps(out))
PY
echo "prom_exit=$?"
echo "=== done ==="
touch /tmp/ramp-done
