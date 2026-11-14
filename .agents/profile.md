# Nexus — agent profile

## Stack
- Java 27 via the Gradle toolchain; Gradle 9.9.1 (wrapper); the root project `Nexus` holds no code.
- `server`: Spring Boot 4.1.2 — WebMVC, WebSocket, Security, Validation, Actuator, Flyway, JDBC, Data Redis, Mail, Micrometer; PostgreSQL 18, database and schema `nexus` (Flyway `V1`–`V10`).
- `client-sdk`: Java client generated from `api/v1/openapi.yaml` (OpenAPI Generator 7.27.0) into `com.nexus.sdk`.
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
- `./gradlew gaOnlyCheck`, `./scripts/check-ga-pins.sh`, `./scripts/check-image-pins.sh` — release-policy gates.
- Sonar: `./gradlew sonar` (plugin 7.5.0.8588), quality gate in CI.

## Layout
- `server/src/main/java/com/nexus/server/{identity,social,messaging,groups,administration,infrastructure}`.
- `server/src/main/resources/db/migration` — forward-only Flyway migrations; never edit an applied file.
- `desktop-shared/src/main/java/com/nexus/desktop/shared/{ui,cache,messaging,mls}`.
- `desktop-user` / `desktop-admin` — one FXML view plus controller per screen, `*AppContext` composition root.
- Contracts: `api/v1/openapi.yaml` (REST) and `api/v1/events.yaml` (WebSocket events).
- Documentation: `docs/` only — start at `docs/README.md`. There is no `docs/plan.md` and no `docs/CI-CD.md`; those were replaced.
- Work log: `.superpowers/sdd/nexus/progress.md` (history, not a specification).

## Invariants
- JDK 27 and UTF-8; do not lower `options.release`.
- Ship GA releases only: no alpha, beta, RC, milestone, early-access or snapshot dependency anywhere, including GitHub Actions.
- Every container reference carries an exact version tag or a `@sha256:` digest; never `:latest`.
- Put a test in the source set matching its dependencies; integration tests need Docker, never mocks.
- PostgreSQL is published on `127.0.0.1:5432` only.
- `.env` is untracked and meant for Compose; never commit credentials.
- Group encryption is MLS-style, not RFC 9420: never describe groups as RFC 9420 or as fully E2EE in docs, UI copy or release notes.
- The name is Nexus everywhere: package `com.nexus`, schema `nexus`, env `NEXUS_*`, properties `nexus.*`.

## Capabilities
- Skills: `java-spring-service`, `postgres-data`, `container-images`, `mermaid-diagrams`, `systematic-debugging`.
- Project MCP: `spring-tools` (`.opencode/opencode.jsonc`), started in `server/` with the project name `server`; run `getProjectDiagnostics` before claiming Spring work is done. `server/gradlew` and `server/gradle` are symlinks to the root wrapper so the language server resolves the Gradle model; they are local tooling, not repo code.
- `postgres-mcp` is unsatisfiable (`pglast==7.2.0`), `mcp-postgres` is broken on Node 26, and the HenkDz server exposes writes; do not retry them. Use `docker exec -i nexus-db-1 psql -U nexus -d nexus`.
- Performance harness: `docker-compose.perf.yml` with `perf/k6/load.js` (steady target) and `perf/ramp-run.sh` + `perf/k6/ramp.js` (staged ceiling ramp, analysed by `perf/ramp-analyze.py`); measured results live in `docs/performance.md`.

- Mandatory UI/UX skill: `.agents/skills/nexus-ui-ux/SKILL.md`. Read `docs/ui-ux-design-system.md` and `docs/ui-ux-tooling.md` for every desktop visual/interaction task. Native JavaFX only; generic web-design output is not authoritative.
- UI dependency decisions: AtlantaFX 3.0.0 is the preferred JavaFX 27+ theme candidate and Ikonli 12.4.0 is the preferred GA icon candidate, but neither is approved as a production dependency until its documented proof of concept passes. Do not weaken the GA-only policy; Ikonli 12.5.0 Early Access is not allowed.
