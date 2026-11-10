# Repository Guidelines

## Project Structure

Production Java code lives in `src/main/java`, organized under `com.haichutieu.chatsystem` into client, server, data access, DTO, and utility packages. FXML, CSS, images, and default properties are in `src/main/resources`. Tests are split by requirements: fast unit tests in `src/test/java`, PostgreSQL and socket integration tests in `src/integrationTest/java`, and JavaFX tests in `src/fxTest/java`. Build and CI guidance is in `docs/CI-CD.md`.

## Build, Test, and Run

Use the Gradle wrapper and JDK 27:

- `./gradlew build` compiles and builds the application, running standard checks.
- `./gradlew test` runs fast unit tests without Docker or a display.
- `./gradlew integrationTest` runs PostgreSQL and TCP integration tests; Docker must be available.
- `./gradlew fxTest` runs JavaFX tests; a desktop display or Xvfb is required.
- `./gradlew fullTest` runs all three test suites and the coverage gate.
- `./gradlew runClient` and `./gradlew runAdmin` launch the desktop clients. See the README for server and database setup.

## Coding Style and Naming

Follow the existing Java package structure and conventions: `UpperCamelCase` for classes, `lowerCamelCase` for methods and fields, and descriptive names matching the feature or domain. Compile with UTF-8. Keep FXML, stylesheets, and image resources in `src/main/resources` and preserve their package-aligned paths. No repository-wide formatter or linter is configured; match nearby code.

## Testing

Write JUnit Jupiter tests in the source set matching their dependencies. Keep ordinary logic tests in `src/test`; use integration tests for real database or socket behavior, and `src/fxTest` for JavaFX toolkit or FXML behavior. Name test classes `*Test` and run the narrow task while iterating, then the relevant broader suite before submitting. Integration tests use Testcontainers and need Docker.

## Commits and Pull Requests

Recent commit subjects are short and action-oriented (for example, `Adjust build.gradle` or `Change tiny logic in searching for users`). Keep commits focused and use a concise imperative subject. PRs should explain the user-visible change, note relevant test commands and results, link related issues when available, and include screenshots for UI changes.

## Configuration and Secrets

Do not commit credentials. Use environment variables or local, untracked configuration for database, mail, and client settings; `.env` is intended for Docker Compose. See the README for supported variables and defaults.
