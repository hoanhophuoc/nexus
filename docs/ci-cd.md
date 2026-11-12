# CI/CD

Five workflows live in `.github/workflows`. Everything they enforce is reproducible locally with
the commands below.

## `ci.yml` — pull requests and main

| Job | Runs | Fails when |
| --- | --- | --- |
| Build, unit & integration tests | `check-ga-pins.sh`, `gaOnlyCheck`, `check-image-pins.sh`, `:desktop-shared:test`, `:desktop-user:test`, `:desktop-admin:test`, `:server:test`, `:server:integrationTest`, coverage report and `coverageVerification`, SonarQube when a token is configured | A pre-release pin, a floating image, a failing test, or coverage below the floor |
| Database identity pre-flight | `./scripts/test-database-identity.sh` against a throwaway PostgreSQL | Any documented exit status of `check-database-identity.sh` changes, including a rejected password or an unreachable host being reported as a fresh install |
| JavaFX FXML tests | `xvfb-run -a ./gradlew :desktop-user:fxTest :desktop-admin:fxTest` | FXML or controller wiring breaks |
| Live user journey (isolated Docker) | `./scripts/live-journey.sh`, then verifies no temporary container, volume or network survived | The end-to-end journey fails, or it leaks Docker resources |
| Linux app image | Builds the desktop packaging output on Linux | A packaging step fails |

The two desktop modules take their coverage from their JavaFX suites, so the `fx-test` job uploads
their `fxTest.exec` files as the `fx-coverage` artifact and the analysis job downloads them before
`./gradlew sonar` (`build-test` therefore waits for `fx-test`). Without that data the module
reports were empty, SonarQube counted both UI modules as 0% covered, and the project sat at 38%.
With it they measure about 65% and 85%, so the reported project coverage is about 74%.

`java:S2077` ("Make sure using a dynamically formatted SQL query is safe here") is ignored for the
SQL-building packages only — `server/administration`, `server/groups/Jdbc*`,
`server/messaging/JdbcMessagingRepository`, `server/messaging/OutboxPublisher` and `server/social` —
via `sonar.issue.ignore.multicriteria` in `build.gradle`. Those repositories concatenate constant
fragments (cursor and sort clauses chosen from a fixed whitelist) and bind every value as a JDBC
parameter. Rewriting them into a single constant statement per sort/filter combination would trade
the predicates that `AdminGroupSearchPlanIntegrationTest` asserts for a green rule; the rule stays
active in every other package.

## The gates

| Gate | Command | Protects against |
| --- | --- | --- |
| GA-only dependencies | `./gradlew gaOnlyCheck` | An alpha, beta, milestone, RC, snapshot, preview or EA version on any classpath |
| GA-only external pins | `./scripts/check-ga-pins.sh` | A pre-release GitHub Action SHA, container tag or Dockerfile base image |
| Pinned container images | `./scripts/check-image-pins.sh` | `:latest`, an untagged image, or a tag that pins no version |
| Coverage floor | `./gradlew coverageVerification` | Silent loss of tested behaviour |
| Database plans | `:server:integrationTest` | A cascade or search that stops using its index (see [performance.md](performance.md)) |
| Live journey cleanup | last step of the journey job | Containers, volumes or networks left behind |
| Secret scan | `ggshield secret scan repo git@github.com:hoanhophuoc/nexus.git` | A credential in a pushed commit |

`gitguardian.yaml` runs the same scan in CI: it checks the commits a push or pull request adds,
not the whole history, so a finding that is already in the repository does not mask a new one. The
action and the checkout step are pinned to commit SHAs, and the scan needs the
`GITGUARDIAN_API_KEY` repository secret.

Three values in the history are false positives that cannot be changed without changing behaviour:
the MLS application-key domain label, the loopback address used as a mail host in a test, and the
plaintext of a legacy bcrypt hash a test verifies. `.gitguardian.yaml` lists them with the reason;
the scan reports them as ignored instead of failing.

Container references must carry an exact version tag or a `@sha256:` digest. The current pins:

| Image | Pin |
| --- | --- |
| `postgres` | `18-alpine` |
| `redis` | `8.10-alpine` |
| `gradle` (build stage) | `9.8.2-jdk27` |
| `eclipse-temurin` (runtime stage) | `27-jre` |
| `prom/prometheus` | `v3.16.0` |
| `grafana/k6` | `2.3.0` |

The build image stays on Gradle 9.8.2 because Docker Hub publishes no `9.9.1-jdk27` tag; the
wrapper inside it runs 9.9.1, which is what compiles the project.

## `codeql.yml` and `dependency-review.yml`

CodeQL analyses the Java sources for security defects on a schedule and for pull requests.
Dependency review blocks a pull request that introduces a dependency with a known vulnerability
or an incompatible licence.

## `release.yml` — tags `v*`

1. **Validate**: the tag must be semantic version (`vMAJOR.MINOR.PATCH`) and the commit must be
   on `main`; then the unit and integration suites run.
2. **SBOM**: CycloneDX SBOM for the whole build, uploaded as an artifact.
3. **Package**: native installers for the desktop clients in a matrix build.
4. **Server image**: builds and pushes the versioned image to GHCR. The tag comes from the
   release version; there is no moving `latest` tag in the pipeline.
5. **Publish**: downloads the assets, writes SHA-256 checksums, attests build provenance and
   creates the GitHub release.

A release is only cut from a commit whose gates all passed; the release workflow re-runs the
tests rather than trusting an earlier run.

## Running the gates locally

```bash
./scripts/check-ga-pins.sh
./scripts/check-image-pins.sh
./gradlew gaOnlyCheck coverageVerification
xvfb-run -a ./gradlew fullTest
```

`fullTest` is the closest local equivalent of the CI build job; the live journey is the only
step it does not cover, and it needs Docker.
