# Development

## Toolchain

| Tool | Version | Where it is pinned |
| --- | --- | --- |
| JDK | 27 | `build.gradle` toolchain, `Dockerfile` |
| Gradle | 9.9.1 | `gradle/wrapper/gradle-wrapper.properties` (checksum verified) |
| Spring Boot | 4.1.2 | `build.gradle` |
| JavaFX | 27.0.1 | `desktop-*/build.gradle` |
| PostgreSQL | 18 (`postgres:18-alpine`) | `docker-compose*.yml` |
| Redis | 8.10 (`redis:8.10-alpine`) | `docker-compose*.yml` |

Only GA releases are allowed. `./gradlew gaOnlyCheck` resolves every classpath and fails on an
alpha, beta, milestone, release candidate, snapshot, preview or early-access version;
`./scripts/check-ga-pins.sh` covers the pins Gradle cannot see (action SHAs, image tags,
Dockerfile base images). A pre-release pin is a build failure, not a review comment.

## Layout

```
server/                 Spring Boot service (Java package com.nexus.server)
  src/main/java         controllers, services, Jdbc repositories
  src/main/resources    application.properties, Flyway migrations
  src/test              unit tests
  src/integrationTest   Testcontainers PostgreSQL + Redis tests
client-sdk/             OpenAPI-generated client (com.nexus.sdk)
desktop-shared/         session, encrypted cache, history sync, MLS crypto, UI kit
desktop-user/           user client + fxTest suite
desktop-admin/          admin client + fxTest suite
ui-spike/               AtlantaFX/Ikonli proof of concept; no product module depends on it
api/v1/openapi.yaml     the contract
docs/                   this documentation set
perf/                   k6 scenarios, Prometheus config, seed data
scripts/                gates, live journey, packaging helpers
```

## Commands

| Command | Does |
| --- | --- |
| `./gradlew build` | Compiles every module and runs the standard checks |
| `./gradlew test` | Fast unit tests, no Docker and no display needed |
| `./gradlew :server:integrationTest` | Testcontainers PostgreSQL 18 + Redis 8.10; applies the real migrations |
| `./gradlew fxTest` | JavaFX tests; needs a display or Xvfb (`xvfb-run -a ./gradlew fullTest`) |
| `./gradlew fullTest` | All three suites plus the coverage gate |
| `./gradlew :ui-spike:spikeCheck` | AtlantaFX/Ikonli proof of concept; needs a display or Xvfb, and fails on any unmet check |
| `./gradlew coverageVerification` | Fails below the recorded coverage floor; needs the JavaFX exec data, so run `fullTest` first |
| `./gradlew gaOnlyCheck` | Fails on a pre-release dependency |
| `./scripts/check-ga-pins.sh` | Fails on a pre-release action SHA, image tag or base image |
| `./scripts/check-image-pins.sh` | Fails on a floating or untagged container image |
| `./gradlew :server:bootRun` | Runs the API and WebSocket on `NEXUS_SERVER_PORT` |
| `./scripts/smoke-desktop.sh` | Starts throwaway PostgreSQL and Redis, runs the server, sends and receives a message between two real clients, then cleans up |
| `./gradlew :desktop-user:run` / `:desktop-admin:run` | Runs a desktop client |
| `./gradlew :desktop-user:packageClient` / `:desktop-admin:packageAdmin` | Builds a native installer |
| `./gradlew :client-sdk:openApiGenerate` | Regenerates the SDK from `api/v1/openapi.yaml` |

Integration tests need a Docker daemon. They start throwaway `postgres:18-alpine` and
`redis:8.10-alpine` containers, never a shared database. `fxTest` needs a display; the CI job
installs Xvfb.

## Desktop smoke

`./scripts/smoke-desktop.sh` answers "does the client work against a real server?" without
clicking anything. It starts throwaway `postgres:18-alpine` and `redis:8.10-alpine` containers on
ephemeral host ports (so a database already running on 5432 is untouched), starts
`:server:bootRun` on a free port with `NEXUS_SECURE_COOKIE=false`, waits for
`/actuator/health` to report `UP`, then runs the user client's opt-in test under Xvfb:

```
NEXUS_REAL_SERVER=true ./gradlew :desktop-user:fxTest --tests '*RealServerUserSmokeFxTest' --rerun-tasks
```

`RealServerUserSmokeFxTest` is skipped unless `NEXUS_REAL_SERVER=true`, so `fullTest` never needs a
server. When it runs it registers two unique accounts through `/auth/register`, makes them friends
and creates a direct conversation through the API, signs in as the first through the same
`UserAppContext.signIn` the client uses, renders `shell.fxml` in a real `Stage`, waits for the
connected status, opens that conversation, types into the composer and sends. It then waits for the
bubble to lose its `pending` marker, which happens only after the queued message was accepted, and
checks the message is in the server's history. Then it signs the second account in to its own client
window, opens the same chat and waits for that text to arrive as the counterpart's message rather
than its own — the receive side of the round trip. Finally it switches LIGHT → DARK → LIGHT on the
live shell. A green run covers the SDK, the session cookie, the encrypted cache, sending, receiving
and the shell together.

Everything the script creates — both containers, the server and its Gradle daemon — is removed on
exit, including on failure or Ctrl-C. Its log is `build/smoke/server.log`;
`NEXUS_SMOKE_KEEP=true` leaves the stack running for inspection and `NEXUS_SMOKE_NO_XVFB=true` uses
the current display.

## Tests

| Suite | Module(s) | Content |
| --- | --- | --- |
| `test` | every module | Pure logic: validation, mapping, crypto helpers, formatting |
| `integrationTest` | `server` | Migrations from an empty schema, constraints and transactions, REST auth and CSRF, Redis rate limits and presence, delete cost, search plans |
| `fxTest` | `desktop-user`, `desktop-admin` | FXML loading, controller wiring, JavaFX journeys, light/dark and English/Vietnamese golden screenshots, and the chat timeline benchmark that asserts its responsiveness budgets |

Write the test in the suite that matches its dependencies: no database in `test`, no JavaFX
toolkit outside `fxTest`. Name classes `*Test`. Run the narrow task while iterating and
`./gradlew fullTest` before committing.

The realtime wire contract is checked in as one JSON file per event type under `contract/realtime`
(`message-created`, `message-deleted`, `message-hidden`, `mls-message`, `mls-commit`). The server's
`OutboxPublisherTest` publishes one row of each type through a captured Redis connection and compares
the envelope with the file; `MessageHistorySyncServiceTest` and `MlsGroupSyncServiceTest` parse the
`payload` of those same files, so a renamed field fails in the suite that still expects the old name
instead of both suites staying green while delivery stops. The files are generated by the publisher:
after changing an envelope, run
`./gradlew :server:test --tests '*OutboxPublisherTest*' -PcontractUpdate`, review the diff, and commit
it with the change. Editing a fixture by hand is not supported — the server test rewrites or rejects
it on the next run. Every test task reads the directory through the `nexus.contractDir` system
property and declares it as an input, so an edited fixture re-runs the suites that read it.

Cost and plan tests are gates, not benchmarks: `AccountDeletionCostIntegrationTest`,
`MessageDeleteCostIntegrationTest`, `UserSearchPlanIntegrationTest` and
`AdminUserSearchPlanIntegrationTest` fail when a cascade or a search stops using its index.
Do not relax their assertions; fix the query or add the index.

## Database changes

- Flyway only, forward-only, files `V<n>__description.sql` in
  `server/src/main/resources/db/migration`. Never edit an applied migration.
- Keep the schema name `nexus`; never qualify a table as `public`.
- A new foreign key needs a leading index on the referencing column, otherwise every delete of
  the parent scans the child table. This is measured, not assumed.
- Substring search must target a trigram index (`pg_trgm`) with `LIKE ? ESCAPE '\'`; `POSITION`
  and `lower(...) =` style predicates cannot use those indexes. Build such indexes with
  `WITH (fastupdate = off)` so they work as soon as accounts exist.
- Search predicates are always parameterized. PostgreSQL plans a parameterized `LIKE` with
  default selectivity, so the pool sets `plan_cache_mode = force_custom_plan`; keep it.
- Every migration is exercised by `:server:integrationTest` against an empty database, so a
  broken statement fails the build.

## Conventions

- Package `com.nexus`, modules as in the layout above. `UpperCamelCase` types,
  `lowerCamelCase` members, SQL text in `Jdbc*` repositories.
- Ukrainian… no: user-visible strings live in the i18n bundles under
  `desktop-*/src/main/resources/com/nexus/.../i18n`, not inline in code.
- No ORM, no `SELECT *`, no string-concatenated user input.
- Comments explain why a non-obvious thing is done; the code explains what.
- Match the surrounding style; no formatter is enforced.

## Local troubleshooting

| Symptom | Cause |
| --- | --- |
| `:server:integrationTest` fails immediately | Docker daemon not running |
| `NoSuchMethodError` after pulling | Stale build output; run `./gradlew clean` (this deletes `build/perf`, so copy evidence first) |
| JavaFX tests fail with "no DISPLAY" | Wrap with `xvfb-run -a` |
| Login redirects but nothing works | `NEXUS_SECURE_COOKIE=true` over plain HTTP; set it to `false` locally |
