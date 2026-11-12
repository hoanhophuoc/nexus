# Nexus

Nexus is a real-time messaging platform: a Spring Boot server, two JavaFX desktop clients
(user and administrator) and a generated Java client SDK. Direct chats, groups with
end-to-end encryption, presence, friends, moderation and an operations dashboard all run on
one PostgreSQL schema and one Redis instance.

The name, the Java package (`com.nexus`), the database schema (`nexus`), the environment
prefix (`NEXUS_`) and the container names all derive from that single name. Nothing else
carries a former project name.

## Quick start

```bash
cp .env.example .env                 # adjust passwords if you like; .env is git-ignored
docker compose up -d db redis        # PostgreSQL 18 and Redis 8.10 on loopback
./gradlew :server:bootRun            # API and WebSocket on http://localhost:8080
./gradlew :desktop-user:run          # user client
./gradlew :desktop-admin:run         # administrator client
```

The server applies its Flyway migrations on start, so an empty database is enough.

## Modules

| Module | What it is |
| --- | --- |
| `server` | Spring Boot service: REST API, authenticated WebSocket, Flyway migrations, Redis presence and fan-out, Prometheus meters |
| `client-sdk` | Java client generated from `api/v1/openapi.yaml` |
| `desktop-shared` | Shared client runtime: session, encrypted local cache, history sync, MLS crypto, UI kit |
| `desktop-user` | User desktop application |
| `desktop-admin` | Administrator desktop application |
| `api` | The OpenAPI contract that both the server and the SDK are checked against |

## Documentation

All documentation lives in [`docs/`](docs/README.md). Start there; it is the single place
that describes this project and it is kept in step with the code.

- [`docs/architecture.md`](docs/architecture.md) — modules, data model, message path, encryption
- [`docs/api.md`](docs/api.md) — REST and WebSocket contract, authentication, pagination
- [`docs/development.md`](docs/development.md) — toolchain, build, tests, conventions
- [`docs/operations.md`](docs/operations.md) — configuration, deployment, monitoring, backups
- [`docs/ci-cd.md`](docs/ci-cd.md) — pipelines and the gates that protect them
- [`docs/performance.md`](docs/performance.md) — measured throughput, latency and database costs

## Quality gates

The build fails on a pre-release dependency, a floating container tag, a coverage regression,
or an architecture rule violation. Current state: 137 tests across seven suites (4 skipped by
design), all green; `./gradlew fullTest` runs them with the coverage gate.
