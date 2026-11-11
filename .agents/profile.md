# Chat-System — agent profile

## Stack
- Java 27 via the Gradle toolchain; Gradle 9.9.1 (wrapper); the root project holds no code.
- `server`: Spring Boot 4.1.2 — WebMVC, WebSocket, Security, Validation, Actuator, Flyway, JDBC, Data Redis, Mail, Micrometer; PostgreSQL 18 with the `chat_v1` schema (Flyway `V1`–`V9`).
- `client-sdk`: Java client generated from `api/v1/openapi.yaml` (OpenAPI Generator 7.27.0).
- `desktop-shared`: JavaFX 27.0.1 design system, i18n, encrypted cache, MLS-style group crypto (Bouncy Castle 1.86).
- `desktop-user` / `desktop-admin`: JavaFX 27.0.1 FXML applications; jpackage owns their installers.
- Redis 8.10 for presence, rate limits and event fan-out; Jackson 3.3.0; Password4j Argon2id; JUnit Jupiter 6.1.

## Commands
- `./gradlew :server:test` — server unit tests, no Docker or display.
- `./gradlew :server:integrationTest` — PostgreSQL 18 + Redis via Testcontainers; requires Docker.
- `./gradlew :desktop-shared:test` — cache, sync and MLS crypto tests.
- `./gradlew :desktop-user:fxTest :desktop-admin:fxTest` — JavaFX tests; requires a display or Xvfb.
- `./gradlew fullTest` — every module suite plus `coverageVerification`.
- `./gradlew coverageReport` — JaCoCo XML/HTML for `:server` and `:desktop-shared`.
- `./gradlew :desktop-user:run` / `:desktop-admin:run` / `:server:bootRun` — start an app locally.
- `./gradlew :desktop-user:packageClient` / `:desktop-admin:packageAdmin` — jpackage installers.
- Sonar: `./gradlew sonar` (plugin 7.5.0.8588), quality gate in CI.

## Layout
- `server/src/main/java/com/haichutieu/chatsystem/server/{identity,social,messaging,groups,administration,infrastructure}`.
- `server/src/main/resources/db/migration` — forward-only Flyway migrations; never edit an applied file.
- `desktop-shared/src/main/java/.../desktop/shared/{ui,cache,messaging,mls}`.
- `desktop-user` / `desktop-admin` — one FXML view plus controller per screen, `*AppContext` composition root.
- Contracts: `api/v1/openapi.yaml` (REST) and `api/v1/events.yaml` (WebSocket events).
- Workstream notes and verification log: `.superpowers/sdd/chat-system-rewrite/progress.md`; plan: `docs/plan.md`.

## Invariants
- JDK 27 and UTF-8; do not lower `options.release`.
- Ship GA releases only: no alpha, beta, RC, milestone, early-access or snapshot dependency anywhere, including GitHub Actions.
- Put a test in the source set matching its dependencies; integration tests need Docker, never mocks.
- PostgreSQL is published on `127.0.0.1:5432` only.
- `.env` is untracked and meant for Compose; never commit credentials.
- Group encryption is MLS-style, not RFC 9420: never describe groups as RFC 9420 or fully E2EE in docs, UI copy or release notes.

## Capabilities
- Skills: `java-spring-service`, `postgres-data`, `container-images`, `mermaid-diagrams`, `systematic-debugging`.
- Project MCP: `spring-tools` (`.opencode/opencode.jsonc`), started in `server/` with the project name `server`; run `getProjectDiagnostics` before claiming Spring work is done. `server/gradlew` and `server/gradle` are symlinks to the root wrapper so the language server resolves the Gradle 9 model; they are local tooling, not repo code.
- `postgres-mcp` is unsatisfiable (`pglast==7.2.0`), `mcp-postgres` is broken on Node 26, and the HenkDz server exposes writes; do not retry them. Use `docker exec -i chat-system-db-1 psql -U chat -d chat_system`.
- Performance harness: `docker-compose.perf.yml` with `perf/k6/chat-load.js` (steady target) and `perf/ramp-run.sh` + `perf/k6/chat-ramp.js` (staged ceiling ramp, analysed by `perf/ramp-analyze.py`); measured results are recorded in `docs/plan.md` and the ledger.
