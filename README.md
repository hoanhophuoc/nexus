# **Real-Time Chat System**

[![CI](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/ci.yml/badge.svg)](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/ci.yml)
[![CodeQL](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/codeql.yml/badge.svg)](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/codeql.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=hoanhophuoc_Chat-System&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=hoanhophuoc_Chat-System)

A desktop chat product built around a Spring Boot server: two JavaFX applications (user
and admin) talk to a versioned REST API and an authenticated WebSocket, with PostgreSQL
for durable state and Redis for presence, rate limiting and event fan-out.

The CI/CD, coverage and performance programme is documented in [docs/CI-CD.md](docs/CI-CD.md).
The rewrite plan and its verification log are in [docs/plan.md](docs/plan.md) and
[.superpowers/sdd/chat-system-rewrite/progress.md](.superpowers/sdd/chat-system-rewrite/progress.md).

## **Architecture**

```
┌────────────────┐   ┌────────────────┐        ┌──────────────────────────┐        ┌──────────────┐
│ desktop-user   │   │ desktop-admin  │  REST  │        server            │  JDBC  │ PostgreSQL 18│
│ (JavaFX + FXML)│   │ (JavaFX + FXML)│ ─────► │ Spring Boot 4 / WebMVC   │ ─────► │  chat_v1     │
└────────────────┘   └────────────────┘  WS    │ /api/v1  and  /ws/v1     │        └──────────────┘
        │                    │           ─────► │ revocable session cookie │               │
        └────────┬───────────┘                  └──────────┬───────────────┘               │
                 │                                         │ pub/sub, presence            │
          ┌──────▼───────┐                          ┌──────▼───────┐                       │
          │ client-sdk   │  generated from          │  Redis 8.10  │                       │
          │ (OpenAPI)    │  api/v1/openapi.yaml     └──────────────┘                       │
          └──────────────┘                                                                 │
                 └──────────────── desktop-shared (ui, cache, mls, sync) ─────────────────┘
```

## **Modules**

| Module | What it is |
| --- | --- |
| `server` | Spring Boot service: versioned REST, authenticated WebSocket, Flyway migrations, Redis presence/fan-out, Prometheus meters |
| `client-sdk` | Java HTTP client generated from `api/v1/openapi.yaml`; shared by both desktop apps |
| `desktop-shared` | Design system and theme tokens, i18n bundles, encrypted local cache, history sync, MLS-style group crypto |
| `desktop-user` | JavaFX user application (login, shell, account, groups, report, settings) with jpackage installers |
| `desktop-admin` | JavaFX admin application (accounts, reports, groups, registrations, analytics, audit) |

The Gradle root project holds no code: it owns `fullTest`, the coverage gate and the
Sonar/SBOM configuration.

## **Features**

### User application
- Registration, login, logout, password change and single-use expiring password reset.
- Profile view/edit, user discovery, friend requests, accept/decline/cancel, unfriend.
- Blocking and unblocking; blocked users disappear from discovery and cannot start new conversations.
- Direct chat with cursor history, search over synchronized local history, delete for me,
  clear history, and sender-only delete for everyone.
- Groups: create with key-package exchange, rename, add/remove members, promote/demote,
  leave, encrypted group messaging, recovery code.
- Spam reporting with explicitly selected message evidence and a visible consent step.

### Admin application
- Account listing, filtering, sorting and paging; create, edit, delete, lock/unlock,
  admin password reset, per-user login history and friend lists.
- Group listing and member/admin inspection.
- Report review with only the evidence the reporter submitted, plus uphold/dismiss decisions.
- Registrations by date range, monthly charts, friend/friend-of-friend and activity analytics.
- An audit record for every moderation action.

## **Technologies**

| Technology | Version | Purpose |
| --- | --- | --- |
| Java | **27** | Language and runtime (Gradle toolchain) |
| Gradle | **9.9.1** | Build and packaging (wrapper) |
| Spring Boot | **4.1.2** | Server, WebMVC, WebSocket, Security, Actuator |
| JavaFX | **27.0.1** | Desktop clients |
| PostgreSQL | **18** | Primary store, `chat_v1` schema managed by Flyway **13.12.1** |
| Redis | **8.10** | Presence, rate limits, realtime fan-out |
| Jackson | **3.3.0** | JSON on both sides of the API |
| HikariCP | **7.1.0** | Connection pool |
| Password4j | **1.8.4** | Argon2id password hashing (32 MiB, 3 iterations, 1 lane) |
| Bouncy Castle | **1.86** | MLS-style group cryptography |
| JUnit Jupiter / Testcontainers | **6.1.3 / 2.0.5** | Tests, including real PostgreSQL and Redis |
| Micrometer + Prometheus | Boot-managed | Server meters for the load tests |

## **Requirements**

- **Docker Engine 24+** with **Docker Compose v2** — runs PostgreSQL, Redis and the server.
- **JDK 27** — only to build or run the clients and the server directly. The Gradle wrapper
  downloads the configured toolchain when it is missing.
- A desktop session for the two JavaFX clients.

## **Quick start**

```bash
cp .env.example .env          # set POSTGRES_PASSWORD and CHAT_REDIS_PASSWORD
docker compose up --build -d
docker compose ps             # db, redis and server should be healthy
curl --fail http://localhost:8080/actuator/health
```

Flyway creates the `chat_v1` schema on the first start. Then run a client from the host:

```bash
./gradlew :desktop-user:run     # user client
./gradlew :desktop-admin:run    # admin client
```

Both clients connect to `http://localhost:8080` by default. Set `CHAT_SERVER_ORIGIN` to point
them at another server, for example `CHAT_SERVER_ORIGIN=https://chat.example.com`.

### Creating an admin account

Registration always creates a normal user. Register through the user client, then promote
that account:

```bash
docker compose exec -T db psql -U chat -d chat_system -c \
  "UPDATE chat_v1.user_account SET role = 'admin' WHERE username = 'your-username';"
```

Admin access is checked server-side from the session, so sign in again afterwards.

### Stop and reset

```bash
docker compose down       # stop the containers, keep the database volume
docker compose down -v    # stop and delete the volumes (fresh start)
```

## **Configuration**

Configuration is layered; later sources win:

1. Defaults in `server/src/main/resources/application.properties`.
2. Environment variables (below).

`.env` is read **only by Docker Compose**. A server started from an IDE or the command line
reads process environment variables; it does not load `.env`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `CHAT_DB_URL` | `jdbc:postgresql://localhost:5432/chat_system` | JDBC URL |
| `CHAT_DB_USER` / `CHAT_DB_PASSWORD` | `chat` / `chat` | Database credentials |
| `CHAT_SERVER_PORT` | `8080` | HTTP port |
| `CHAT_SECURE_COOKIE` | `true` | Require HTTPS for the session cookie; set `false` only for local HTTP |
| `CHAT_REDIS_HOST` / `CHAT_REDIS_PORT` / `CHAT_REDIS_PASSWORD` | `localhost` / `6379` / empty | Redis |
| `CHAT_DB_POOL_SIZE` | `16` | Hikari pool size |
| `CHAT_MAX_CONNECTIONS` | `20000` | Tomcat max connections (10,000 sockets exceed the 8,192 default) |
| `CHAT_PRESENCE_HEARTBEAT_MS` / `CHAT_PRESENCE_TTL_SECONDS` | `30000` / `90` | Presence refresh and expiry |
| `CHAT_EMAIL_*` | empty | SMTP for password-reset mail; the feature is disabled when unset |
| `CHAT_MAIL_HEALTH_ENABLED` | `false` | Include SMTP in readiness |

## **Local development without Docker**

```bash
CHAT_DB_URL=jdbc:postgresql://localhost:5432/chat_system \
CHAT_DB_USER=chat \
CHAT_DB_PASSWORD=<password from .env> \
CHAT_REDIS_PASSWORD=<password from .env> \
./gradlew :server:bootRun
```

Running the Compose database only (`docker compose up -d db redis`) lets the server run from
an IDE while PostgreSQL stays containerised; port 8080 must then be free.

## **Testing**

```bash
./gradlew :server:test                    # server unit tests
./gradlew :server:integrationTest         # PostgreSQL 18 + Redis via Testcontainers (Docker)
./gradlew :desktop-shared:test            # cache, sync and MLS crypto
./gradlew :desktop-user:fxTest :desktop-admin:fxTest   # JavaFX tests (display or Xvfb)
./gradlew fullTest                        # every suite plus the coverage gate
./gradlew coverageReport                  # JaCoCo XML/HTML for the product modules
```

`:server:integrationTest` and the JavaFX suites are the two slow gates; `docs/CI-CD.md`
describes what each job runs and how the coverage floor is set.

There is also an opt-in end-to-end journey that needs only Docker:

```bash
./scripts/live-journey.sh
```

It builds its own server and test-runner images, starts throwaway PostgreSQL, Redis and
server containers, and runs the JavaFX user journey headlessly. It never touches the normal
Compose stack.

## **Security model**

- Passwords are stored as **Argon2id** hashes (Password4j); older BCrypt hashes still verify
  and are upgraded on the next successful login.
- Sessions are opaque httpOnly cookies; only token hashes are stored, and sessions are
  revocable. Identity and role always come from the session, never from a client-supplied id.
- Authentication and reset endpoints are rate limited in Redis; reset tokens are single-use
  and expiring, and responses are neutral for unknown addresses.
- Admins see only the message evidence a reporter explicitly submitted, and every moderation
  action is audited.
- Group messages are end-to-end encrypted **MLS-style**, built on Bouncy Castle `bcmls`
  primitives. This is **not** RFC 9420 conformance; documentation and UI copy must not claim it.
- Direct messages are protected by TLS in transit; server-side storage encryption at rest is
  not implemented. The local client cache is encrypted with a key derived from the password.

## **Project layout**

```
api/v1/openapi.yaml            REST contract (the SDK is generated from it)
api/v1/events.yaml             WebSocket event contract
server/src/main/java/.../server/{identity,social,messaging,groups,administration,infrastructure}
server/src/main/resources/db/migration   Flyway V1-V6 (forward-only)
desktop-shared/src/main/java/.../desktop/shared/{ui,cache,messaging,mls}
desktop-user, desktop-admin    one FXML view and controller per screen
scripts/live-journey.sh        isolated end-to-end journey (Docker only)
docker-compose.yml             PostgreSQL 18 + Redis 8.10 + server
docker-compose.perf.yml        isolated load-test stack (k6, Prometheus, JFR)
```

## **Performance**

`docker-compose.perf.yml` runs the server under a memory limit with Prometheus scraping
`/actuator/prometheus` and a k6 WebSocket scenario behind the `load` profile. The recorded
baseline is 10,000 concurrent sessions at 100 messages/second: delivery p95 ≈ 110 ms,
p99 ≈ 116 ms, zero failed checks, and a flat live heap over a one-hour soak. A staged ramp
(`perf/ramp-run.sh`) then walked the session count up until something broke: 20,000 sessions
is `CHAT_MAX_CONNECTIONS`, not the machine, and with that cap raised the same build holds
**30,000 sessions at p95 110 ms / p99 189 ms** with no failed requests. Reproduction steps,
the per-level table and the generator limits are in [docs/CI-CD.md](docs/CI-CD.md).

## **Troubleshooting**

- **`Address already in use`** — another process owns port 8080; stop it or set `CHAT_SERVER_PORT`.
- **`password authentication failed for user "chat"`** — the server is not using the Compose
  password. Set `CHAT_DB_PASSWORD`; Java never reads `.env`. A volume that predates a password
  change needs `docker compose down -v`.
- **Clients fail with "Unable to open DISPLAY"** — no desktop session; run them from a
  graphical login.
- **Password reset mail fails** — `CHAT_EMAIL_*` is unset; the feature is disabled until a
  working SMTP account is configured.
- **Inspect the database** — `docker compose exec db psql -U chat -d chat_system`.

## **License**

MIT.
