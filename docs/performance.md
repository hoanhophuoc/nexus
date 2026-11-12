# Performance

Every number here was produced by a command in `perf/` or by a test in `:server:integrationTest`.
Re-run them rather than trusting the page.

## How to reproduce

```bash
docker compose -f docker-compose.perf.yml up -d db redis server prometheus
./perf/ramp-run.sh                     # staged k6 ramp, Prometheus ranges, JFR profile
docker compose -f docker-compose.perf.yml down
```

The perf stack uses offset ports (database 15432, server 18080, Prometheus 19090) and enables
`pg_stat_statements`. `perf/load.js` drives steady traffic; `perf/ramp.js` walks through stages
and reports p95/p99 per stage from the receiving socket. The server writes a JFR profile to
`build/perf/app.jfr`.

## Capacity

Measured on one server instance (8 vCPU, 31 GiB) with a 20,000-connection ceiling that was later
raised, while 45 sender VUs pushed 100 messages per second into 9,000 seeded direct conversations:

```bash
PLAN=10000:90:120,14000:60:90,18000:60:90 USERS=18000 ./perf/ramp-run.sh
```

| Sessions | p95 | p99 | Notes |
| --- | --- | --- | --- |
| 10,000 | 111 ms | 194 ms | Heap 0.85 GiB of 3 GiB |
| 14,000 | 113 ms | 143 ms | Heap 1.19 GiB |
| 18,000 | 113 ms | 190 ms | Heap 1.52 GiB, 0 of 51,024 requests failed, outbox lag 0 s |
| 19,999 | 367 ms | 1.27 s | Hit exactly `NEXUS_MAX_CONNECTIONS`; sends dropped from 100/s to 10.2/s |
| 22,000 | 108 ms | 817 ms | After raising the cap |
| 26,000 | 111 ms | 340 ms | Heap 2.08 GiB of 6 GiB |
| 30,000 | 110 ms | 189 ms | Max 593 ms, 0 of 23,024 requests failed, 30,122 open descriptors, GC pause 0.16% of wall time |

The first stage reports the slowest single delivery (1.6 s) because it also carries the connection
ramp; per-stage p95 stays at 111–113 ms from 10,000 to 18,000 sessions. Heap grows by roughly
0.2 GiB per 4,000 sessions, and HikariCP never held more than 8 of its connections.

The 19,999 result is the important one: the first wall was the connection cap, not the server.
Latency is flat from 10,000 to 30,000 sessions, and CPU stayed far from saturation, so the
single instance is not near its knee at three times the original target.

Two generator-side limits appear before the server does, and both look like server faults until
the telemetry is read:

- The k6 container's ephemeral port range tops out near 28,000 connections to one address;
  30,000 and 34,000 targets failed with `connect: cannot assign requested address` while the
  server sat at 8% CPU. Widen `net.ipv4.ip_local_port_range` (and enable `tcp_tw_reuse`).
- The k6 container has its own file-descriptor limit; raise it together with the server's.

## Database cost of the hot paths

Measured with `EXPLAIN (ANALYZE, BUFFERS)` on a fixture of 20,000 accounts, 2,000 conversations,
400,000 messages, 400,000 outbox events and 203,000 memberships:

| Path | Plan | Time |
| --- | --- | --- |
| Outbox poll (every 100 ms) | Index Scan on the pending partial index | 0.78 ms |
| Outbox mark published | Index-only scan plus update | 1.93 ms |
| Message page, first and by cursor | Membership index, then message cursor index | 1.12 ms / 0.56 ms |
| Conversation list / group list | Index-only scans | 0.05 ms |
| Friends page | BitmapOr over both friendship indexes | 0.16 ms |
| Incoming friend requests (20k rows) | `friend_request_target_idx` | 0.38 ms |
| Member batch for 100 direct chats | Index-only scan on the membership primary key | 3.77 ms |
| User search, term that matches nobody | BitmapOr of two trigram indexes | 0.127 ms (was 6.6 ms scanning the table) |
| Admin account filter, same term | BitmapOr of three trigram indexes | index-backed |
| Delete one message (400k outbox events) | Index lookups only | was 20.96 ms in the FK trigger, now a seek |

### What the audits changed

- `V7` indexed every foreign key pointing at `user_account`; before it, deleting an account
  walked the whole message table.
- `V8` indexed the outbox's own foreign keys and `conversation.direct_low_id`. Deleting a single
  message used to spend 20.96 ms inside `message_outbox_message_id_fkey` scanning 400,000
  events.
- `V9` replaced `POSITION` search with `pg_trgm` indexes and escaped `LIKE`, cutting the
  no-match case from a 6.6 ms full traversal to 0.127 ms. It is built with `fastupdate = off`
  because a freshly populated index otherwise stays in the pending list and the planner falls
  back to a sequential scan (4.7 ms measured) until a vacuum runs.
- `V10` added the email trigram index the admin filter needs.
- The pool sets `plan_cache_mode = force_custom_plan`: PostgreSQL plans a parameterized `LIKE`
  with default selectivity, and the reused generic plan went back to walking the primary key
  (11.2 ms against 0.2 ms).

Guarding measures: `AccountDeletionCostIntegrationTest`, `MessageDeleteCostIntegrationTest`,
`UserSearchPlanIntegrationTest` and `AdminUserSearchPlanIntegrationTest` fail if those plans stop
using their indexes.

## Sizing guidance

- One instance handled 30,000 WebSocket sessions at ~110 ms p95 on the test hardware; treat that
  as the order of magnitude for a similar machine, not a guarantee.
- Keep `instances × NEXUS_DB_POOL_SIZE` below PostgreSQL `max_connections` (the perf stack runs
  300).
- Outbox lag, not CPU, is the first sign of trouble: watch `nexus.outbox.oldest_age_seconds`.
