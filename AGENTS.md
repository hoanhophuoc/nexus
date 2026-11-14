# Repository Guidelines

## Project structure

Nexus is a Gradle multi-module build. Production code lives under
`<module>/src/main/java/com/nexus/...`; resources with package-aligned paths live under
`<module>/src/main/resources/com/nexus/...`. `server` holds the Spring Boot service
(`com.nexus.server`: identity, social, messaging, groups, administration), `client-sdk` is
generated from `api/v1/openapi.yaml` into `com.nexus.sdk`, and the three desktop modules cover
the shared runtime, the user client and the administrator client. Tests are split by
requirements: fast unit tests in `src/test/java`, PostgreSQL/Redis integration tests in
`server/src/integrationTest/java`, and JavaFX tests in `desktop-*/src/fxTest/java`.
`docs/` is the single home for documentation; see `docs/README.md`.

## Build, test, run

Use the Gradle wrapper and JDK 27:

- `./gradlew build` — compiles and runs the standard checks.
- `./gradlew test` — unit tests, no Docker, no display.
- `./gradlew :server:integrationTest` — Testcontainers PostgreSQL 18 and Redis 8.10; Docker required.
- `./gradlew fxTest` — JavaFX tests; a display or `xvfb-run -a` is required.
- `./gradlew fullTest` — all suites plus the coverage gate.
- `./gradlew gaOnlyCheck`, `./scripts/check-ga-pins.sh`, `./scripts/check-image-pins.sh` — release-policy gates.
- `./gradlew :server:bootRun`, `:desktop-user:run`, `:desktop-admin:run` — run the service and clients.

## Coding style

`UpperCamelCase` types, `lowerCamelCase` members, package `com.nexus`, UTF-8 sources. SQL lives
in `Jdbc*` repositories as parameterized text. FXML, CSS and images stay in the
package-aligned resource paths. No formatter or linter is configured; match nearby code.

## Testing

Name classes `*Test` and put each test in the suite whose dependencies it needs. Run the narrow
task while iterating and `./gradlew fullTest` before submitting. Cost and plan integration tests
assert that cascades and searches use their indexes; do not relax them to make a build pass.

## Commits and pull requests

Commit subjects are short, imperative and action-oriented. Keep commits focused. A pull request
explains the user-visible change, lists the commands run and their results, links related
issues, and includes screenshots for UI changes.

## Configuration and secrets

Never commit credentials. Use `.env` (git-ignored, template in `.env.example`) or environment
variables; the full variable list is in `docs/operations.md`. Defaults in the repository are
development-only values.

## Documentation

Every change that alters a table, endpoint, variable, gate or measured number updates the
matching document under `docs/` in the same commit. Documents describe the current system only;
history belongs to git.

## UI/UX design contract (mandatory)

Every task that creates, changes, reviews, styles, animates, localizes or tests desktop UI must read and follow `.agents/skills/nexus-ui-ux/SKILL.md` **before editing**. The canonical product-specific rules are `docs/ui-ux-design-system.md` and `docs/ui-ux-tooling.md`; inspect those files rather than relying on previous agent context or generic web-design instructions.

- Nexus desktop UI is native JavaFX 27.0.1 with FXML and JavaFX CSS, on JDK 27. Do not apply web-only frameworks, browser checks or web interaction conventions to the desktop clients.
- Preserve and extend the existing `desktop-shared` theme/design-system facilities. Do not introduce a second theme manager or duplicate semantic token system.
- Every meaningful visual change must be verified in both LIGHT and DARK themes, in English and Vietnamese where copy is affected, using the existing JavaFX FX tests, rendered-control contrast checks and reviewed golden screenshots.
- Do not add UI dependencies without following the compatibility, license, GA-release, proof-of-concept and packaging gates in `docs/ui-ux-tooling.md`.
