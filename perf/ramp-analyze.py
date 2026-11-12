#!/usr/bin/env python3
"""Turn a staged ramp run into a per-stage table.

Reads the k6 summary (/tmp/ramp-k6.log), the run window (/tmp/ramp-start-ms) and the
Prometheus ranges dumped by perf/ramp-run.sh (/tmp/ramp-prom.json), then prints p95/p99/max
per stage next to the server-side signals that explain the ceiling.

Usage: perf/ramp-analyze.py [PLAN] [K6_LOG]
"""
import json
import re
import sys

DEFAULT_PLAN = "10000:90:120,14000:60:90,18000:60:90,22000:60:90"
UNITS = {"ns": 1e-6, "us": 1e-3, "µs": 1e-3, "ms": 1.0, "s": 1000.0, "m": 60000.0}
LINE_RE = re.compile(r"^([a-z0-9_]+)\W*:\s*(.*)$", re.IGNORECASE)
PAIR_RE = re.compile(r"([a-z0-9_()%]+)=([\d.]+(?:ns|us|µs|ms|s|m)?)")


def to_ms(text):
    match = re.fullmatch(r"([\d.]+)(ns|us|µs|ms|s|m)", text.strip())
    return float(match.group(1)) * UNITS[match.group(2)] if match else None


def parse_summary(path):
    """metric name -> {"stats": {k: ms}, "value": raw first token} from the k6 summary block."""
    metrics = {}
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = LINE_RE.match(line.strip())
            if not match:
                continue
            name, rest = match.group(1).lower(), match.group(2)
            stats = {}
            for key, value in PAIR_RE.findall(rest):
                if value.endswith("%"):
                    stats[key] = float(value.rstrip("%")) / 100
                else:
                    # A count has no unit; k6 prints it as a bare integer.
                    milliseconds = to_ms(value)
                    stats[key] = milliseconds if milliseconds is not None else float(value)
            value = None
            first = rest.split()[0] if rest.split() else None
            if first:
                try:
                    value = float(first.rstrip("%")) / (100 if first.endswith("%") else 1)
                except ValueError:
                    value = None
            metrics[name] = {"stats": stats, "value": value, "raw": rest.strip()}
    return metrics


def series_values(blob):
    """Flatten a Prometheus range response into {timestamp: summed value}."""
    if not blob or "data" not in blob:
        return {}
    total = {}
    for result in blob["data"].get("result", []):
        for timestamp, value in result.get("values", []):
            try:
                total[float(timestamp)] = total.get(float(timestamp), 0.0) + float(value)
            except ValueError:
                continue
    return total


def within(values, start_s, end_s):
    return [value for timestamp, value in values.items() if start_s <= timestamp <= end_s]


def main():
    plan = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PLAN
    log_path = sys.argv[2] if len(sys.argv) > 2 else "/tmp/ramp-k6.log"
    try:
        start_ms = int(open("/tmp/ramp-start-ms").read().strip())
    except OSError:
        sys.exit("missing /tmp/ramp-start-ms")
    try:
        prom = json.load(open("/tmp/ramp-prom.json"))
    except (OSError, ValueError):
        prom = {}
    summary = parse_summary(log_path)

    stages = []
    cursor = 0
    for entry in plan.split(","):
        target, ramp_s, hold_s = (int(part) for part in entry.split(":"))
        stages.append({"target": target, "hold_start": cursor + ramp_s, "end": cursor + ramp_s + hold_s})
        cursor += ramp_s + hold_s

    heap = series_values(prom.get('jvm_memory_used_bytes{area="heap"}'))
    gc_count = series_values(prom.get("jvm_gc_pause_seconds_count"))
    gc_sum = series_values(prom.get("jvm_gc_pause_seconds_sum"))
    ws = series_values(prom.get("nexus_ws_sessions"))
    outbox = series_values(prom.get("nexus_outbox_oldest_age_seconds"))

    def gb(value):
        return f"{value / 1024 ** 3:.2f}"

    def mean(values):
        return sum(values) / len(values) if values else 0.0

    print("stage target hold(s) delivered  sent   p95(ms)  p99(ms)   max(ms)  "
          "ws avg/max   heap_avg(GiB) heap_max  gc/s  gc_pause%  outbox_max(s)")
    for index, stage in enumerate(stages):
        hold_start_s = start_ms / 1000 + stage["hold_start"]
        hold_end_s = start_ms / 1000 + stage["end"]
        trend = summary.get(f"nexus_delivery_stage{index}_ms", {})
        stats = trend.get("stats", {})
        sent = summary.get(f"nexus_sent_stage{index}", {}).get("value") or 0
        ws_stage = within(ws, hold_start_s, hold_end_s)
        heap_stage = within(heap, hold_start_s, hold_end_s)
        count_stage = within(gc_count, hold_start_s, hold_end_s)
        sum_stage = within(gc_sum, hold_start_s, hold_end_s)
        outbox_stage = within(outbox, hold_start_s, hold_end_s)
        span = max(hold_end_s - hold_start_s, 1)
        pauses = (count_stage[-1] - count_stage[0]) if len(count_stage) > 1 else 0
        pause_time = (sum_stage[-1] - sum_stage[0]) if len(sum_stage) > 1 else 0
        print(
            f"{index:>5} {stage['target']:>6} {stage['end'] - stage['hold_start']:>7} "
            f"{stats.get('count', 0):>9.0f} {sent:>6.0f} "
            f"{stats.get('p(95)', float('nan')):>8.0f} {stats.get('p(99)', float('nan')):>8.0f} "
            f"{stats.get('max', float('nan')):>8.0f}  "
            f"{mean(ws_stage):>5.0f}/{max(ws_stage) if ws_stage else 0:>5.0f}   "
            f"{gb(mean(heap_stage)):>12} {gb(max(heap_stage) if heap_stage else 0):>8}  "
            f"{pauses / span:>4.2f} {100 * pause_time / span:>9.2f}  "
            f"{max(outbox_stage) if outbox_stage else 0:>11.1f}"
        )

    print()
    print("global:")
    for name in ("checks", "http_req_failed", "nexus_sockets_rejected", "dropped_iterations",
                 "iterations", "vus_max", "http_reqs", "nexus_messages_sent", "nexus_messages_received",
                 "http_req_duration", "nexus_delivery_ms"):
        metric = summary.get(name)
        if metric:
            print(f"  {name}: {metric['raw']}")


if __name__ == "__main__":
    main()
