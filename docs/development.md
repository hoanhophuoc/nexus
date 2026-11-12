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
| `./gradlew coverageVerification` | Fails below the recorded coverage floor; needs the JavaFX exec data, so run `fullTest` first |
| `./gradlew gaOnlyCheck` | Fails on a pre-release dependency |
| `./scripts/check-ga-pins.sh` | Fails on a pre-release action SHA, image tag or base image |
| `./scripts/check-image-pins.sh` | Fails on a floating or untagged container image |
| `./gradlew :server:bootRun` | Runs the API and WebSocket on `NEXUS_SERVER_PORT` |
| `./gradlew :desktop-user:run` / `:desktop-admin:run` | Runs a desktop client |
| `./gradlew :desktop-user:packageClient` / `:desktop-admin:packageAdmin` | Builds a native installer |
| `./gradlew :client-sdk:openApiGenerate` | Regenerates the SDK from `api/v1/openapi.yaml` |

Integration tests need a Docker daemon. They start throwaway `postgres:18-alpine` and
`redis:8.10-alpine` containers, never a shared database. `fxTest` needs a display; the CI job
installs Xvfb.

## Tests

| Suite | Module(s) | Content |
| --- | --- | --- |
| `test` | every module | Pure logic: validation, mapping, crypto helpers, formatting |
| `integrationTest` | `server` | Migrations from an empty schema, constraints and transactions, REST auth and CSRF, Redis rate limits and presence, delete cost, search plans |
| `fxTest` | `desktop-user`, `desktop-admin` | FXML loading, controller wiring, JavaFX journeys |

Write the test in the suite that matches its dependencies: no database in `test`, no JavaFX
toolkit outside `fxTest`. Name classes `*Test`. Run the narrow task while iterating and
`./gradlew fullTest` before committing.

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
