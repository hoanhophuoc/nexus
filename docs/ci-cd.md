# CI/CD

Five workflows live in `.github/workflows`. Everything they enforce is reproducible locally with
the commands below.

## `ci.yml` — pull requests and main

| Job | Runs | Fails when |
| --- | --- | --- |
| Build, unit & integration tests | `check-ga-pins.sh`, `gaOnlyCheck`, `check-image-pins.sh`, `:desktop-shared:test`, `:desktop-user:test`, `:desktop-admin:test`, `:server:test`, `:server:integrationTest`, the JavaFX coverage artifact, coverage report and `coverageVerification`, SonarQube when a token is configured | A pre-release pin, a floating image, a failing test, or coverage below the floor in any of the four product modules |
| Database identity pre-flight | `./scripts/test-database-identity.sh` against a throwaway PostgreSQL | Any documented exit status of `check-database-identity.sh` changes, including a rejected password or an unreachable host being reported as a fresh install |
| JavaFX FXML tests | `xvfb-run -a ./gradlew :desktop-user:fxTest :desktop-admin:fxTest` | FXML or controller wiring breaks |
| Live user journey (isolated Docker) | `./scripts/live-journey.sh`, then verifies no temporary container, volume or network survived | The end-to-end journey fails, or it leaks Docker resources |
| Linux app image | Builds the desktop packaging output on Linux, then runs `scripts/verify-app-image.sh --image <dir> --launch` on both images | A packaging step fails, an image is missing a jar the app needs, or its launcher exits instead of showing its first window |

The two desktop modules take their coverage from their JavaFX suites, so the `fx-test` job uploads
their `fxTest.exec` files as the `fx-coverage` artifact and the analysis job downloads it before the
coverage report, the gate and `./gradlew sonar` (`build-test` therefore waits for `fx-test`).
Without that data the module reports were empty, SonarQube counted both UI modules as 0% covered,
and the project sat at 38%. With it they measure 65.43% and 84.86% of lines, which takes the reported
project coverage from 38% to 67%. Together the four product modules and both desktop suites measure
74.42% of lines and 51.05% of branches, so the bundle floor is one full point below each: 73% of
lines and 50% of branches. The package floors are rounded down the same way.

`java:S2077` ("Make sure using a dynamically formatted SQL query is safe here") is still ignored for
`server/administration`, `server/messaging/JdbcMessagingRepository` and
`server/messaging/OutboxPublisher`, through `sonar.issue.ignore.multicriteria` in `build.gradle`.
Those statements choose their ORDER BY from a whitelist, so their text is assembled at runtime even
though every value is a bound parameter.

`server/social` no longer needs the exclusion. `JdbcSocialRepository` now runs five compile-time
statements and always binds a cursor, using the nil UUID for the first page (`AND u.id > ?` with
`00000000-0000-0000-0000-000000000000`, below every generated id). `UserSearchPlanIntegrationTest`
still plans both trigram bitmap scans, so the constant text costs no index.

The measurements behind the remaining exclusions — 20,000 groups on `postgres:18-alpine`, group
filter planned with `EXPLAIN (ANALYZE, FORMAT TEXT)`:

| Statement | Plan | Execution |
| --- | --- | --- |
| constant text, pattern bound, custom plan | `Bitmap Index Scan on conversation_group_name_trgm_idx` | 0.039 ms |
| `$1::text IS NULL OR lower(c.name) LIKE $2`, custom plan | the same trigram bitmap scan | 0.045 ms |
| the same statement, generic plan | `Seq Scan on conversation` | 5.433 ms |

Constant text therefore does not cost the index — the datasource sets `plan_cache_mode =
force_custom_plan` — but only one statement at a time. Finishing the job means giving each remaining
query a constant statement per sort/direction pair, binding the cursor the same way, and keeping its
plan test green; until then the rule stays excluded there instead of hiding a regression.

## The gates

| Gate | Command | Protects against |
| --- | --- | --- |
| GA-only dependencies | `./gradlew gaOnlyCheck` | An alpha, beta, milestone, RC, snapshot, preview or EA version on any classpath |
| GA-only external pins | `./scripts/check-ga-pins.sh` | A pre-release GitHub Action SHA, container tag or Dockerfile base image |
| Pinned container images | `./scripts/check-image-pins.sh` | `:latest`, an untagged image, or a tag that pins no version |
| Coverage floor | `./gradlew coverageVerification` (needs the desktop `fxTest` exec data, so run `fullTest` first) | Silent loss of tested behaviour in any of the four product modules, including the bundle (73% of lines, 50% of branches), `desktop-user` (65% of lines) and `desktop-admin` (84%) |
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
3. **Package**: native installers for the desktop clients in a matrix build on `ubuntu-latest`,
   `windows-latest` and `macos-26`. Two checks run before the assets are collected:
   `scripts/verify-app-image.sh --input <dir>` reads the jars jpackage is about to copy, and
   `scripts/verify-app-image.sh --installer <file>` unpacks each finished installer (`dpkg-deb -x`
   for the `.deb`, `hdiutil` for the `.dmg`) and looks for the shared module inside it, so the
   artifact that is published is what gets checked. The MSI is not unpacked — neither `msiexec /a`
   nor 7-Zip laid the payload out reliably on the runner — so its own `File` table is queried
   instead, which lists exactly the files the installer will write. An installer cannot ship without
   the shared desktop module; that
   was the failure that made every client abort at startup with
   `NoClassDefFoundError: com/nexus/desktop/shared/ui/ThemeManager`, because the module's jar had not
   been built when the packaging task copied the runtime classpath. The macOS job needs a current
   arm64 label: GitHub retired every macOS 14 label on 2026-11-02, and a job that names a retired
   label is cancelled before its first step ("The configuration 'macos-14-arm64-us-default' is not
   supported") rather than failing visibly. Because **Publish** requires the whole matrix, that
   silent cancellation also removes the GitHub release, so keep this entry on a supported label.
4. **Server image**: builds and pushes the versioned image to GHCR. The tag comes from the
   release version; there is no moving `latest` tag in the pipeline. The push is retried up to three
   times because GHCR sometimes rejects the manifest write with a bare `denied` after accepting every
   layer — that is what failed `v1.0.0` on its first attempt, and the same command passed unmodified
   on the re-run. Retrying is safe: layers are content-addressed and the tag only appears once the
   manifest lands.
5. **Publish**: downloads the assets, writes SHA-256 checksums, attests build provenance and
   creates the GitHub release. The job never checks out the repository, so every `gh` call passes
   `--repo "$GITHUB_REPOSITORY"`; without it `gh release create` cannot discover the repository and
   aborts with `fatal: not a git repository`. When the release already exists, as on a re-run, the
   assets are re-uploaded with `--clobber` instead of failing the release.

A release is only cut from a commit whose gates all passed; the release workflow re-runs the
tests rather than trusting an earlier run.

The macOS installer is not signed or notarized: the workflow has no `codesign` or `notarytool` step
and no Apple Developer ID certificate. Gatekeeper therefore refuses the first launch of
`nexusClient.dmg` and `nexusAdmin.dmg`; a double click only offers to move the app to the Bin. To
run it anyway a user has to right-click the app, choose **Open**, and confirm, or allow it under
**System Settings → Privacy & Security → Open Anyway**, or clear the quarantine flag
(`xattr -dr com.apple.quarantine "/Applications/Nexus Client.app"`). No macOS machine in this
project has opened either `.dmg` yet, so treat them as unverified builds of the same app image the
Linux `.deb` ships.

Signing and notarizing them needs, in the `macos-arm64` package job:

1. A **Developer ID Application** certificate exported as `.p12` with its password, an Apple ID
   with an app-specific password (or an App Store Connect API key with issuer and key id) and the
   team id, held as repository secrets and exposed only to that job.
2. A keychain step before packaging — `security create-keychain`, `security import`,
   `security set-key-partition-list` — so `codesign` can reach the identity.
3. A hardened runtime and a timestamp on the app: `jpackage --mac-sign
   --mac-signing-key-user-name "Developer ID Application: …" --mac-signing-keychain …`, or
   `codesign --deep --force --options runtime --timestamp` over the app bundle before the `.dmg`
   is built.
4. `xcrun notarytool submit <dmg> --wait` with those credentials, then `xcrun stapler staple` on
   the `.dmg` and the app, so the ticket travels with the download.
5. `spctl --assess --type execute` in the job as the proof that Gatekeeper accepts the result; the
   current package job has no such check because nothing is signed.

## Running the gates locally

```bash
./scripts/check-ga-pins.sh
./scripts/check-image-pins.sh
./gradlew gaOnlyCheck
xvfb-run -a ./gradlew fullTest
# After :desktop-user:packageClient -PpackageType=app-image
./scripts/verify-app-image.sh --image desktop-user/build/package/nexusClient/out/nexusClient --launch
```

`fullTest` is the closest local equivalent of the CI build job; the live journey is the only
step it does not cover, and it needs Docker. `coverageVerification` on its own is only meaningful
after the JavaFX suites ran: the gate measures `desktop-user` and `desktop-admin` from their
`fxTest.exec`, and it fails with a pointer to `fullTest` when that data is missing rather than
counting those modules as uncovered.
