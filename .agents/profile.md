# Chat-System — agent profile

## Stack
- Java 27 via the Gradle toolchain; Gradle 9.8.x; JPMS (`system.chatsystem`).
- Root modules: JavaFX 27.0.1 clients, Hibernate ORM 7.4.14 (Jakarta Persistence 3.2), Jackson 3.2.4, SLF4J 2.0, Password4j (Argon2id), JUnit Jupiter 6.1.
- `server` module: Spring Boot 4.1.2 — WebMVC, WebSocket, Security, Validation, Actuator, Flyway, JDBC, Data Redis, Mail, Testcontainers.
- PostgreSQL 18 and Redis 7 via Docker Compose; the socket server uses one virtual thread per connection.

## Commands
- `./gradlew build` — compile plus standard checks.
- `./gradlew test` — fast unit tests, no Docker or display.
- `./gradlew integrationTest` — PostgreSQL and TCP integration tests; requires Docker.
- `./gradlew fxTest` — JavaFX toolkit and FXML tests; requires a display or Xvfb.
- `./gradlew fullTest` — all three suites plus the coverage gate.
- `./gradlew runClient` / `./gradlew runAdmin` — desktop clients.
- Sonar: `./gradlew sonar` (plugin 7.5.0.8588), quality gate in CI.

## Layout
- Production Java: `src/main/java/com/haichutieu/chatsystem`; resources: `src/main/resources` (FXML, CSS, images).
- Test source sets are split by dependency: `src/test/java`, `src/integrationTest/java`, `src/fxTest/java`.
- Modules: `server`, `client-sdk`, `desktop-shared`, `desktop-user`, `desktop-admin`.
- Flyway migrations: `server/src/main/resources/db/migration` (`V1`–`V4`).

## Invariants
- JDK 27 and UTF-8; do not lower `options.release`.
- Put a test in the source set matching its dependencies; integration tests need Docker, never mocks.
- PostgreSQL is published on `127.0.0.1:5432` only.
- PostgreSQL 18 keeps data in a versioned subdirectory of `/var/lib/postgresql`; do not "fix" that volume mount.
- `.env` is untracked and meant for Compose; never commit credentials.
- Flyway migrations are forward-only; never edit an applied `V*` file.

## Capabilities
- Skills: `java-spring-service`, `postgres-data`, `container-images`, `mermaid-diagrams`, `systematic-debugging`.
- Project MCP: none. `postgres-mcp` is unsatisfiable (`pglast==7.2.0`), `mcp-postgres` is broken on Node 26, and the HenkDz server exposes writes; do not retry them. Use `docker exec -i chat-system-db-1 psql -U chat -d chat_system`.
- Workstream notes: `.superpowers/sdd/chat-system-rewrite/progress.md`.
- Both a plain Hibernate/JPA layer and a Spring Boot server exist; check which module you are in before applying patterns.
