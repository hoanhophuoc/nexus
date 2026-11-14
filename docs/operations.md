# Operations

## Configuration

Every value has a working default for local development. Set them through the environment
(`.env` for Compose, or the orchestrator) — never in a committed file.

### Server

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEXUS_SERVER_PORT` | `8080` | HTTP and WebSocket port |
| `NEXUS_SERVER_HOST` | `0.0.0.0` | Interface the server binds |
| `NEXUS_SECURE_COOKIE` | `true` | `Secure` flag on the session cookie; set `false` only for local HTTP |
| `NEXUS_VIRTUAL_THREADS` | `true` | Run blocking JDBC and Redis calls on virtual threads |
| `NEXUS_MAX_CONNECTIONS` | `20000` | Tomcat connection ceiling; the measured capacity limit |
| `NEXUS_ACCEPT_COUNT` | `500` | Tomcat accept backlog |
| `NEXUS_TOMCAT_THREADS` | `200` | Maximum request worker threads |
| `NEXUS_SCHEDULER_THREADS` | `2` | Scheduler pool; keeps presence heartbeats ahead of the outbox |

### Database

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEXUS_DB_URL` | `jdbc:postgresql://localhost:5432/nexus` | JDBC URL |
| `NEXUS_DB_USER` / `NEXUS_DB_PASSWORD` | `nexus` / `nexus` | Credentials; change outside development |
| `NEXUS_DB_POOL_SIZE` | `16` | Hikari maximum pool size |
| `NEXUS_DB_POOL_MIN_IDLE` | `4` | Hikari minimum idle connections |

Compose also reads `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` to initialize the
container; they default to `nexus`.

### Redis

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEXUS_REDIS_HOST` | `localhost` | Redis host |
| `NEXUS_REDIS_PORT` | `6379` | Redis port |
| `NEXUS_REDIS_PASSWORD` | empty | Redis password; Compose sets `change-me` |
| `NEXUS_PRESENCE_TTL_SECONDS` | `90` | Presence key lifetime |
| `NEXUS_PRESENCE_HEARTBEAT_MS` | `30000` | Client heartbeat interval |

### Email (password reset)

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEXUS_EMAIL_HOST` / `NEXUS_EMAIL_PORT` | `localhost` / `25` | SMTP endpoint |
| `NEXUS_EMAIL_USERNAME` / `NEXUS_EMAIL_PASSWORD` | empty | SMTP credentials |
| `NEXUS_EMAIL_AUTH` / `NEXUS_EMAIL_STARTTLS` | `false` / `false` | SMTP security |
| `NEXUS_EMAIL_FROM` | `no-reply@localhost` | Sender address |
| `NEXUS_MAIL_HEALTH_ENABLED` | `false` | Include SMTP in readiness; keep off when mail is optional |

### Scripts

`scripts/live-journey.sh` builds its own images and reads `NEXUS_LIVE_SERVER_IMAGE`,
`NEXUS_LIVE_TEST_IMAGE`, `NEXUS_LIVE_GRADLE_IMAGE`, `NEXUS_LIVE_GRADLE_HOME`, plus
`NEXUS_REAL_SERVER`, `NEXUS_REAL_SERVER_ORIGIN`, `NEXUS_REAL_USERNAME`, `NEXUS_REAL_PASSWORD`,
`NEXUS_REAL_BOB_USERNAME`, `NEXUS_REAL_BOB_PASSWORD`, `NEXUS_REAL_ADMIN_USERNAME`,
`NEXUS_REAL_ADMIN_PASSWORD` for the end-to-end journeys. The journeys default to throwaway
containers; pointing them at real credentials is an explicit choice.

## Deployment

### Container image

`Dockerfile` builds `nexus-server.jar` in a `gradle:9.8.2-jdk27` stage and runs it on
`eclipse-temurin:27-jre` as the unprivileged user `nexus` (uid 10001), exposing 8080. It does
not bundle a database or Redis.

### Compose (development and small deployments)

```bash
cp .env.example .env
docker compose up -d db redis     # loopback only: 127.0.0.1:5432, 127.0.0.1:6379
./gradlew :server:bootRun         # or run the image next to them
```

`docker-compose.yml` defines project `nexus` with services `db` (PostgreSQL 18, volume
`postgres-data`), `redis` (Redis 8.10 with AOF and a password) and `server`. The database and
Redis ports are published on loopback only, so an IDE-run server can reach them without
exposing them to the network.

### Performance stack

`docker-compose.perf.yml` is a separate project (`nexus-perf`) with offset ports so it can run
beside development: database `127.0.0.1:15432`, server `127.0.0.1:18080`, Prometheus
`127.0.0.1:19090`. It enables `pg_stat_statements` and records a JFR profile to
`build/perf/app.jfr`. See [performance.md](performance.md).

## Health and observability

| Surface | What it exposes |
| --- | --- |
| `GET /actuator/health` | Liveness and readiness, including the database; SMTP only when enabled |
| `GET /actuator/info` | Build metadata |
| `GET /actuator/metrics` | Micrometer meters |
| `GET /actuator/prometheus` | Prometheus scrape endpoint; keep it on the private network |

Application meters:

| Meter | Type | Meaning |
| --- | --- | --- |
| `nexus.outbox.publish` | timer | Time to publish one batch |
| `nexus.outbox.published` | counter | Events published |
| `nexus.outbox.batch_rows` | gauge | Rows in the last batch |
| `nexus.outbox.oldest_age_seconds` | gauge | Age of the oldest pending event, measured by the database clock — the delivery-latency alarm |
| `nexus.outbox.publish_failures` | counter | Batches whose Redis publish failed |
| `nexus.outbox.max_attempts` | gauge | Highest `attempts` a row reached in the last failed batch — a row stuck retrying |
| `nexus.outbox.abandoned` | counter | Events given up on, tagged `reason`: `encode` (the payload cannot be serialised) or `retries` (five failed publishes) |
| `nexus.ws.delivered` | counter | Frames delivered to clients |
| `nexus.ws.sessions` | gauge | Open WebSocket sessions |

Alert on `nexus.outbox.oldest_age_seconds` growing, on `nexus.outbox.max_attempts` staying above a
few attempts (rows backed off for retry keep it raised until one publishes), on any increase of
`nexus.outbox.abandoned` (it only ever grows), and on
`nexus.ws.sessions` approaching `NEXUS_MAX_CONNECTIONS`: delivery stalled, publishes failing, events
given up on, connection ceiling. A batch that publishes returns `max_attempts` to 0, so the alert
clears itself once Redis recovers; a poll that finds nothing pending does not, which keeps the alert
raised while a capped row is still waiting to be requeued.

A failed publish pushes the row's `next_attempt_at` out by 2, 4, 8 and then 16 seconds, each stretched
or shortened by up to a quarter, so a row is retried about half a minute after its first failure
instead of on every 100 ms poll. Measured from the four waits the schedule actually writes, a row
survives an outage of **22.5 to 37.5 seconds** — 30 seconds nominal — before its fifth failure gets
it abandoned; nothing shorter is lost. The jitter matters when more than one publisher runs: without
it every replica backs off identically and they all retry in the same millisecond. A row that reaches five failed publishes is never polled again;
each batch that abandons rows logs the first three event ids plus a count, and the row records
`abandoned_at`, `abandon_reason` (`encode` or `retries`) and `last_error` so the decision to requeue
it does not need the logs. A payload Redis itself rejects — too large, a value it will not accept —
costs only its own row: when the batch publish fails, the rows are retried one at a time and the ones
that go out are marked published.

List and requeue the abandoned rows with the operator command. It runs instead of the scheduler,
prints its result and exits, so no second publisher starts polling the same table:

```
./gradlew :server:bootRun --args='--chat.messaging.outbox-admin=list'
./gradlew :server:bootRun --args='--chat.messaging.outbox-admin=requeue'
./gradlew :server:bootRun --args='--chat.messaging.outbox-admin=requeue:<event-id>,<event-id>'
```

`list` prints up to 50 rows with their reason and last error; `requeue` clears `attempts`,
`next_attempt_at`, `abandoned_at`, `abandon_reason` and `last_error` on every abandoned row, so the
next poll picks them up. A row requeued with reason `encode` is abandoned again on that poll, which is
the point: the payload is the problem, not the outage.

## Backups and recovery

- **PostgreSQL** is the source of truth. Take `pg_dump -Fc -n nexus` on a schedule and test a
  restore into a scratch database. Every migration is forward-only, so recovery means restoring
  the dump and letting Flyway migrate forward.
- **Redis** holds presence, rate-limit counters and fan-out state, all rebuildable. AOF is
  enabled in Compose, but losing Redis only signs sessions out of presence and clears rate
  limit windows; no user data is lost.
- **Desktop clients** keep an encrypted local cache. Deleting it is safe: the client refetches
  history from the sequence it last acknowledged.

## Scaling and limits

- The server is stateless; scale horizontally and let each instance hold its own WebSocket
  sessions. The measured single-instance capacity is in [performance.md](performance.md).
- Connection limits: raise `NEXUS_MAX_CONNECTIONS` together with the container's file
  descriptor limit (`ulimit -n`) and, for load generators, the kernel's ephemeral port range.
- Database: the pool is sized per instance (`NEXUS_DB_POOL_SIZE`); keep
  `instances × pool ≤ PostgreSQL max_connections`.
- Outbox: the publisher reads pending rows through a partial index in 100 ms cycles. If lag
  rises, add publisher throughput rather than shortening the interval first.

## Upgrades

### Coming from a pre-rename installation

The project, database and schema are now `nexus`. An installation created before the rename
keeps its data in database `chat_system` and schema `chat_v1`; a new server would create an empty
`nexus` schema and look empty. Rename both instead of migrating data:

```sql
ALTER DATABASE chat_system RENAME TO nexus;
-- connected to the renamed database:
ALTER SCHEMA chat_v1 RENAME TO nexus;
```

Flyway's history table moves with the schema, so it sees the migrations already applied and does
not re-run them. Verify with `SELECT * FROM nexus.flyway_schema_history` before starting the
server.

### Check before restarting the server

The server creates its schema when it is missing, which is what a fresh install needs and what a
pre-rename install must never hit: starting against an untouched database migrates an empty
`nexus` schema and every existing row stays in `chat_v1`, invisible. Run the pre-flight check first:

```bash
./scripts/check-database-identity.sh
```

It reads the same variables the server does (`NEXUS_DB_URL`, `NEXUS_DB_USER`,
`NEXUS_DB_PASSWORD`), uses `psql` when installed and otherwise the pinned `postgres:18-alpine`
image through `docker run --network host`.

| Exit | Meaning | Action |
| --- | --- | --- |
| 0 | `nexus` is the schema in use, or this is a fresh install | Start the server |
| 1 | `chat_v1` or `chat_system` still holds the data | Apply the rename above, then re-run the check |
| 2 | The variables cannot be read or the database cannot be reached | Fix the connection first |

A warning (exit 0) means both the current and the pre-rename schema exist: the server only reads
`nexus`, so the old rows stay ignored until they are dropped. A rejected password, an unreachable
host and an unreadable database all end at exit 2 with the server's own message; none of them is
reported as a fresh installation.

`./scripts/test-database-identity.sh` runs every branch above against a throwaway PostgreSQL,
including a wrong password and a port with no database, and fails if an exit status changes.

### Routine upgrade

1. Back up the database.
2. Deploy the new image; Flyway applies pending migrations at start. Migrations are additive and
   backwards compatible within one release.
3. Watch `nexus.outbox.oldest_age_seconds` and the error rate for one outbox cycle (a few
   seconds) after the rollout.
