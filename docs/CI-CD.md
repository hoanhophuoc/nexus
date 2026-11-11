# CI/CD and Quality Pipeline

This project builds with **JDK 27**, **Gradle 9.9.1**, **JavaFX 27.0.1**, **JUnit Jupiter 6.1.3** and **PostgreSQL 18**. The pipeline uses GitHub Actions and SonarQube Cloud; it never downgrades the Java/JavaFX runtime.

## Workflow architecture

| Workflow | Trigger | Responsibility |
| --- | --- | --- |
| `.github/workflows/ci.yml` | PRs to `main`, pushes to `main`, merge queue, manual | Compile, unit tests, PostgreSQL/socket integration tests, JaCoCo, Sonar analysis and quality gate, JavaFX tests under Xvfb, an isolated Docker live user journey, Linux app-image packaging |
| `.github/workflows/release.yml` | Tags matching `v*` | Validate the tag is on `main`, run tests, generate SBOM, build native installers on Linux/Windows/macOS, publish the server Docker image, create checksums and attestations, publish the GitHub Release |
| `.github/workflows/codeql.yml` | PRs to `main`, pushes to `main`, weekly schedule | Java CodeQL analysis |
| `.github/workflows/dependency-review.yml` | Pull requests to `main` | Block dependency changes with high-severity vulnerabilities |
| `.github/dependabot.yml` | Weekly | Grouped Gradle and GitHub Actions updates |

Every workflow declares least-privilege permissions; write scopes are confined to the jobs that need them (CodeQL security events, GHCR packages, release contents and attestations). Every external action is pinned to a full 40-character commit SHA with its release version in a comment. Normal CI uses `pull_request`, never `pull_request_target`; forked PRs never receive `SONAR_TOKEN`.

## Local build and test commands

```bash
./gradlew clean build                      # compile and assemble every module
./gradlew :server:test                     # server unit tests (no Docker, no display)
./gradlew :server:integrationTest          # PostgreSQL 18 + Redis via Testcontainers (Docker)
./gradlew :desktop-shared:test             # shared client, cache and MLS tests
./gradlew :desktop-user:fxTest :desktop-admin:fxTest   # JavaFX tests (display or Xvfb)
./gradlew fullTest                         # every module suite plus the coverage gate
./gradlew coverageReport                   # XML and HTML coverage for the product modules
./gradlew coverageVerification             # anti-regression coverage gate
./gradlew gaOnlyCheck                      # fail on any pre-release dependency
./scripts/check-ga-pins.sh                 # fail on a pre-release action, image or base image
./gradlew cyclonedxBom                     # CycloneDX SBOM
./gradlew :server:bootRun                  # start the server locally
./gradlew :desktop-user:run :desktop-admin:run         # start the desktop apps
```

The release policy is GA only, so two checks enforce it instead of review. `gaOnlyCheck` resolves every module classpath (`runtime`, `test`, `integrationTest`, `fxTest`) and fails on any alpha, beta, RC, milestone, early-access, preview, snapshot or dev version — it reports the module, the configuration and the offending coordinate. `check-ga-pins.sh` reads the release comment next to each pinned Action commit SHA plus the Compose, Dockerfile and workflow image tags, because those pins never appear in the Gradle graph. The `Build, unit & integration tests` job runs both before compiling, so a pre-release pin cannot reach `main`.

`:server:integrationTest` requires a running Docker daemon. It starts throwaway `postgres:18-alpine` and `redis:8.10-alpine` containers, applies the real Flyway migrations and exercises the REST and WebSocket surfaces. It never reads production credentials.

`fxTest` requires a display. Linux CI runs both JavaFX suites under Xvfb; locally a normal desktop session is enough.

### Golden screenshot baselines

The golden suites keep two reviewed baselines: `golden/` for local Linux desktops and `golden-ci/` for the hosted Ubuntu runner. CI sets `GOLDEN_BASELINE=ci`, so it compares against runner-generated images at 0.75% differing pixels; local runs default to `golden/`. The bundled `Chat System Sans` font keeps glyphs stable, but FreeType rasterization differs enough between distributions that one baseline cannot serve both environments.

Regenerate the local baseline from a desktop session:

```bash
ADMIN_UPDATE_GOLDENS=true USER_UPDATE_GOLDENS=true xvfb-run -a ./gradlew :desktop-user:fxTest :desktop-admin:fxTest
```

Regenerate `golden-ci/` only on the hosted runner with `GOLDEN_BASELINE=ci` and the same update flags; do not copy local screenshots into it. When a JavaFX test fails, CI uploads both the generated `golden-actual/` images and the reports in the `fx-test-reports` artifact so the difference can be reviewed.

The `Live user journey (isolated Docker)` job requires only Docker and network access. It runs `./scripts/live-journey.sh`, which builds its own server and test-runner images, starts throwaway PostgreSQL/Redis/server containers, provisions a temporary admin, runs `RealServerUserJourneyFxTest` headlessly, and removes the temporary stack. It does not use the Compose stack or an Xvfb display, and the job fails if any `chat-system-live-*` container, network or volume remains afterwards.

## Performance harness

`docker-compose.perf.yml` is an isolated stack (offset host ports 15432/18080/19090) with a tuned PostgreSQL, Redis 8.10, the server under a memory limit, Prometheus scraping `/actuator/prometheus` every 5 s, and a k6 runner behind the `load` profile. The server writes a JFR recording to `build/perf/app.jfr` on exit for JMC or `jfr view`.

```bash
mkdir -p build/perf
docker compose -f docker-compose.perf.yml up -d db redis server prometheus
docker compose -f docker-compose.perf.yml exec -T db psql -U chat -d chat_system \
    -v users=10000 -f - < perf/seed.sql
docker compose -f docker-compose.perf.yml run --rm \
    -e SESSIONS=10000 -e USERS=10000 -e RATE=100 -e RAMP=60s -e WARMUP=60s -e HOLD=60s k6
docker compose -f docker-compose.perf.yml down   # flushes build/perf/app.jfr
```

`perf/seed.sql` creates deterministic users, sessions, friendships and one direct conversation per pair, so k6 derives every identifier from an index and needs no fixture file. Sessions are seeded directly, so the run measures socket capacity, message persistence, outbox fan-out and delivery, not the Argon2id login path. `perf/k6/chat-load.js` ramps 0 → `SESSIONS` sockets, holds them, and sends `RATE` messages/second; delivery latency is measured on the receiving socket and fails the run when p95 ≥ 1 s or p99 ≥ 2 s. The run has three phases: `RAMP`, then `WARMUP` seconds of traffic at `RATE`, then the measured plateau of `HOLD` seconds. The warm-up exists because the first load on a fresh JVM pays class loading and JIT compilation: the same binary reported p99 590 ms without it and p99 148 ms on the next run. Warm-up deliveries are reported separately as `chat_delivery_warmup_ms`, so thresholds and the summary only cover the plateau; senders stamp the phase into the message content, so the receiving socket attributes each sample without a shared clock. Values used by the last measured baseline are in `.superpowers/sdd/chat-system-rewrite/progress.md`.

### Ramp to the ceiling

`perf/k6/chat-ramp.js` holds several session counts in one run and reports delivery latency per stage, which is how the API ceiling was measured instead of assumed:

```bash
PLAN=20000:60:60,30000:20:90 USERS=30000 PERF_MAX_CONNECTIONS=50000 PERF_SERVER_MEM=6g \
    bash perf/ramp-run.sh
python3 perf/ramp-analyze.py 20000:60:60,30000:20:90
```

`PLAN` is `targetVUs:rampSeconds:holdSeconds` per stage, `USERS` must cover the largest stage, and the three `PERF_*` variables set the ceiling under test rather than editing the Compose file. The runner builds the stack, seeds the fixtures, records the settings in `/tmp/ramp-meta.txt`, runs k6, then dumps the Prometheus ranges that `perf/ramp-analyze.py` reads. Remember that every stage starts from a fresh JVM: a short first stage reports cold-start cost, not capacity.

Two walls are on the generator, not the server, and both are easy to mistake for a server fault. `CHAT_MAX_CONNECTIONS` caps sessions exactly (a 22,000 target stalls at 19,999 and requests then hit the 60 s client timeout while heap, GC and Hikari stay flat). The k6 container's ephemeral port range caps sockets near 28,000 per destination address, which surfaces as `connect: cannot assign requested address` plus a retry storm; the Compose file widens it to 1024-65535. Past roughly 30,000 sessions the generator also needs about 7 GiB of RAM, so a higher ramp needs a second host.

The harness does not replace a multi-host soak: k6 and the server share one machine, there is no network latency injection, and the plateau is much shorter than the one-hour soak the release gate asks for.

## Test suites and coverage

| Suite | Module | Tests | What it covers |
| --- | --- | --- | --- |
| `test` | `server` | 44 | Identity and profile validation, security filters, session and reset rules, admin authorization, group roles, MLS routing, authenticated WebSocket handling |
| `integrationTest` | `server` | 29 | Flyway migrations from an empty schema, PostgreSQL constraints and transactions, REST auth and CSRF, Redis rate limits and distributed presence, the cost of deleting an account and of deleting a message, the plan of the user search |
| `test` | `desktop-shared` | 29 | Encrypted cache and key derivation, history sync, MLS crypto and state store, theme contrast |
| `test` / `fxTest` | `desktop-user` | 4 / 21 | Bundle parity, FXML loading, account and social flows, MLS group chat, goldens, rendered-control contrast |
| `test` / `fxTest` | `desktop-admin` | 3 / 6 | Resource and design contract, admin flows against a loopback server, goldens |

Current audit run (JaCoCo 0.8.16, `./gradlew coverageReport`, 2026-11-11) over `:server` and `:desktop-shared` unit plus `:server` integration data:

| Package | Line | Branch |
| --- | --- | --- |
| `com.haichutieu.chatsystem.server` (application entry point) | 33.3% | – |
| `com.haichutieu.chatsystem.server.identity` | 70.3% | 51.7% |
| `com.haichutieu.chatsystem.server.social` | 65.1% | 39.6% |
| `com.haichutieu.chatsystem.server.messaging` | 83.1% | 62.1% |
| `com.haichutieu.chatsystem.server.groups` | 86.4% | 67.4% |
| `com.haichutieu.chatsystem.server.administration` | 81.4% | 54.1% |
| `com.haichutieu.chatsystem.server.infrastructure` | 100% | – |
| `com.haichutieu.chatsystem.desktop.shared` | 82.9% | 60.5% |
| `com.haichutieu.chatsystem.desktop.shared.mls` | 86.4% | 55.1% |
| `com.haichutieu.chatsystem.desktop.shared.cache` | 89.7% | 58.2% |
| `com.haichutieu.chatsystem.desktop.shared.messaging` | 64.8% | 46.5% |
| `com.haichutieu.chatsystem.desktop.shared.ui` | 0% | 0% |
| **Bundle** | **75.0%** | **52.3%** |

`coverageVerification` measures only the rewritten modules (`:server` plus `:desktop-shared`, using unit and PostgreSQL/Redis integration execution data) and enforces their measured baseline: 74% line / 51% branch for the bundle, plus per-package floors for identity, social, messaging, groups, administration and the shared MLS, cache and messaging packages. The legacy 19%/13% floors above are gone, so coverage of the rewritten code cannot silently regress; `fullTest` depends on the gate. `./gradlew coverageReport` renders the same scope as HTML and XML at `build/reports/jacoco/coverageReport/`. The gate deliberately uses only unit + integration execution data so the Linux CI build job does not depend on a display. These are regression floors, **not** the desired end state. The aspirational targets remain: authentication/security 90%, business services 85%, persistence 80%, socket protocol/networking 80%, configuration/utilities 85%, JavaFX controllers 70%, overall 75-80%. The current gap is dominated by JavaFX/client controller coverage, which needs toolkit-level interaction tests rather than more DAL tests. SonarQube Cloud's **new code** quality gate remains the pull-request gate. The reports are at `build/reports/jacoco/test/jacocoTestReport.xml` and `build/reports/jacoco/test/html/index.html`.

### Known coverage gaps

- `desktop.shared.ui` is 0% by design in this gate: the theme, `I18n` and component helpers render through the JavaFX toolkit, which the display-free CI job cannot start. They are exercised indirectly by the `fxTest` suites.
- The `desktop-user` and `desktop-admin` controllers are covered by `fxTest` only, so they are outside the coverage gate and outside CI's display-free job.
- The server application entry point and the few exception branches that need an unreachable database remain uncovered.
- SonarQube Cloud analysis has not been executed because no `SONAR_TOKEN` is available in this environment.

## SonarQube Cloud

The root `build.gradle` applies the official SonarScanner for Gradle (`org.sonarqube` 7.5.0.8588) with:

- project key `hoanhophuoc_Chat-System`
- organization `hoanhophuoc`
- host `https://sonarcloud.io`
- JaCoCo XML import
- `sonar.qualitygate.wait=true` (300 s timeout)

### One-time setup

1. Sign in to <https://sonarcloud.io> with the GitHub account that owns the repository.
2. Create or import the project with key `hoanhophuoc_Chat-System` under organization `hoanhophuoc`.
3. If **Automatic Analysis** is enabled for the project, disable it. The Gradle scanner uploads its own analysis and the two modes conflict.
4. Open **My Account → Security → Generate Tokens**, create a project token, and copy it once.
5. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, name it `SONAR_TOKEN`, and paste the token. Never commit it.
6. In SonarQube Cloud, install/configure the GitHub integration for the project so pull requests receive decoration and the quality gate reports back to GitHub.
7. Use the built-in **Sonar way** gate, or the organization's existing gate. Do not invent a custom gate in this repository. The CI step fails when the gate fails.
8. Keep `main` as the long-lived branch and configure the new-code definition as appropriate for the team.

### Pull requests and forks

The analysis step runs on pushes, merge-queue checks, and PRs whose head repository is this repository. Forked PRs run build and tests but skip Sonar, because they must never receive `SONAR_TOKEN`. When the secret is absent, the job prints a GitHub notice and skips analysis; it does not claim a quality-gate result. Configure the secret before relying on the gate.

### Java 27 analyzer compatibility

SonarSource's Java documentation states that LTS versions **8, 11, 17, 21, 25 and all intermediary versions** are fully supported. Java 27 is an intermediary release. The Gradle scanner is run with JDK 27, which satisfies SonarQube Cloud's Java 21+ scanner-runtime requirement. Actual Java 27 analysis could **not** be executed in this environment because no `SONAR_TOKEN` was available. Verify the first CI analysis and review the project's **New Code** gate; do not downgrade the compiler target to make analysis pass.

## Dependency and supply-chain security

- **Dependabot** updates Gradle dependencies and GitHub Actions weekly in grouped PRs.
- **Dependency Review** blocks pull requests that introduce high-severity vulnerable dependencies. On private repositories this requires GitHub Advanced Security; on public repositories it is available by default.
- **CodeQL** scans Java on PRs, `main`, and a weekly schedule. It compiles production sources with `./gradlew compileJava` before analysis.
- **Gradle wrapper integrity**: `gradle-wrapper.properties` contains the official SHA-256 of `gradle-9.9.1-bin.zip`, and `gradle/actions/setup-gradle` validates the wrapper.
- **SBOM**: CycloneDX JSON is generated at `build/reports/cyclonedx/bom.json` and published with each release.
- **Secrets**: `SONAR_TOKEN` is the only required secret. Never add signing keys, database passwords or mail credentials to workflow files. Enable GitHub **Secret Scanning** and **Push Protection** in repository settings.
- Code signing and macOS notarization are not configured. Installers produced by the release workflow are **unsigned** until signing secrets and steps are added.

## Native packaging

The two JavaFX clients are packaged with `jpackage`; the server remains a Docker image.

```bash
./gradlew :desktop-user:packageClient -PpackageType=app-image -PappVersion=1.0.0
./gradlew :desktop-admin:packageAdmin -PpackageType=app-image -PappVersion=1.0.0

# Platform-native installers (run on the matching OS)
./gradlew :desktop-user:packageClient -PpackageType=deb -PappVersion=1.0.0   # Linux
./gradlew :desktop-user:packageClient -PpackageType=msi -PappVersion=1.0.0   # Windows (WiX)
./gradlew :desktop-user:packageClient -PpackageType=dmg -PappVersion=1.0.0   # macOS
```

Outputs are under `build/package/<AppName>/out/`. Each app image contains `bin/`, `lib/app/` (application jars and FXML/CSS resources) and `lib/runtime/` (a bundled Java runtime); users do not need a separate JDK or JavaFX SDK. Desktop packages exclude the Hibernate, PostgreSQL, mail and JAXB jars that only the server uses.

The application is launched from the classpath, so JavaFX prints an "unnamed module" warning. This is expected for this packaging mode. The modular `jlink` route is blocked by Hibernate's automatic modules, and the application was not migrated to explicit modules just for packaging.

The chat server is built and published as `ghcr.io/<owner>/chat-system-server:<version>` from the existing `Dockerfile`; it is never packaged as a desktop installer.

## Release procedure

1. Merge the release changes to `main`.
2. Create and push an annotated semantic-version tag from a commit on `main`:

   ```bash
   git tag -a v1.2.3 -m "Chat System 1.2.3"
   git push origin v1.2.3
   ```

3. The release workflow validates that the tag is `vMAJOR.MINOR.PATCH` and that the commit is an ancestor of `origin/main`.
4. It runs unit and integration tests, then builds `.deb` (Linux x64), `.msi` (Windows x64) and `.dmg` (macOS ARM64) installers, the CycloneDX SBOM, SHA-256 checksums and, on public repositories, artifact attestations.
5. `gh release create` publishes the installers, SBOM and `SHA256SUMS.txt` with generated release notes.
6. The server image is pushed to GHCR with the same version.

Verify a downloaded artifact:

```bash
sha256sum -c SHA256SUMS.txt
gh attestation verify <artifact> --repo <owner>/Chat-System   # public repositories
```

Signing is intentionally absent: without Windows/macOS signing credentials, do not claim the installers are signed.

## Required GitHub repository settings

These cannot be set from files:

- **Branches/rulesets**: protect `main`; require a pull request, at least one approval, conversation resolution, and the CI checks (`Build, unit & integration tests`, `JavaFX FXML tests`, `Live user journey (isolated Docker)`, `Analyze Java`, `Review dependency changes`). Block force pushes and branch deletion.
- **Actions**: set **Actions permissions** to selected actions and allow the pinned actions used here; set the default `GITHUB_TOKEN` permission to read-only.
- **Security**: enable Dependabot alerts, Dependabot security updates, dependency graph, secret scanning and push protection. Enable CodeQL default setup only if you disable the custom CodeQL workflow, otherwise analysis runs twice.
- **Environments**: create a protected `release` environment if approvals are required before publishing; the workflow currently publishes directly from the tag job. Add required reviewers to that environment and reference it in the publish job if needed.
- **SonarQube Cloud**: install the GitHub app/integration so the quality gate appears as a check. Add `SONAR_TOKEN` as described above.

## Verification performed

| Check | Result |
| --- | --- |
| `./gradlew :server:test` | Passed, 44 tests |
| `./gradlew :server:integrationTest` | Passed, 29 tests (PostgreSQL 18 + Redis via Testcontainers) |
| `./gradlew :desktop-shared:test` | Passed, 29 tests |
| `./gradlew fxTest` | Passed, 27 tests (3 skipped in `desktop-user`, 1 in `desktop-admin`) |
| `./gradlew coverageReport` | Passed; XML + HTML generated |
| `./gradlew coverageVerification` | Passed; baseline thresholds enforced (verified the DAL rule fails when raised) |
| `./gradlew fullTest` | Passed, 136 tests across 7 suites (4 skipped by design) plus the coverage gate, on the GA-only stack (Spring Boot 4.1.2, JavaFX 27.0.1) |
| `./gradlew check` | Passed; standard lifecycle runs unit tests (integration/FX remain separate tasks) |
| Architecture rules | Not a Gradle task; `ServerArchitectureTest` runs them inside `./gradlew :server:test` |
| `./gradlew cyclonedxBom` | Passed; `build/reports/cyclonedx/bom.json` |
| `./gradlew :desktop-user:packageClient :desktop-admin:packageAdmin -PpackageType=app-image` | Passed; both images built |
| Packaged client and admin startup smoke tests | Both app images built on JavaFX 27.0.1 and stayed alive for 20 s (timeout 124, no exceptions) both headless and under Xvfb; the server jar manifest reports `Spring-Boot-Version: 4.1.2` |
| Workflow YAML parse | Passed for all workflow files and Dependabot |
| Action SHA pinning | Every action reference uses a full commit SHA |
| `actionlint` 1.7.12 | Passed after fixing one shellcheck glob |
| `zizmor` 1.32.0 | Passed after fixing template injection and release-cache findings |
| `./gradlew sonar --dry-run` | Task graph resolves; analysis not executed (no `SONAR_TOKEN`) |
| `./scripts/live-journey.sh` (no `DISPLAY`, no admin env) | Passed; isolated Docker stack, live journey 1/1, no leftover temporary containers/networks/volumes |
| Workflow lint | `actionlint` 1.7.12: zero findings; PyYAML parse and job/step assertions pass |
| Live journey cleanup guard | Simulated leaked container/network/volume: exit 1 and named all three; after cleanup: exit 0 |
| Windows/macOS packaging | Not executed locally; produced by the release matrix on those runners |
| GitHub Actions execution | Not executed here; workflows are present in the workspace and locally linted |

## Troubleshooting

- **`Address already in use`**: the server or another process owns port 8080. Integration tests use port 0 (ephemeral), so this only affects manual runs.
- **Integration test cannot find Docker**: start Docker and rerun `./gradlew integrationTest`. Testcontainers uses Ryuk; stale containers are removed automatically.
- **JavaFX tests fail without a display**: run `xvfb-run -a ./gradlew fxTest` on Linux, or run from a desktop session.
- **Sonar step skipped**: `SONAR_TOKEN` is absent. Add the repository secret; forked PRs are always skipped by design.
- **Sonar gate fails**: inspect the project's quality gate and the new-code coverage/bugs/vulnerabilities; do not disable `sonar.qualitygate.wait`.
- **`jpackage` fails on Linux**: install `fakeroot` and `dpkg-deb`; the release workflow installs `fakeroot` and builds on Ubuntu.
- **`jpackage` fails on Windows**: the MSI build requires the WiX Toolset on the runner; use the `app-image` type to validate packaging without WiX.
- **Release rejected**: the tag must be `vMAJOR.MINOR.PATCH` and its commit must be on `main`.
- **Coverage report missing**: run `./gradlew test integrationTest jacocoTestReport`; the Sonar task depends on the report.
