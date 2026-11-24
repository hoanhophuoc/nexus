# CI/CD

Six workflows live in `.github/workflows`. Everything they enforce is reproducible locally with
the commands below.

## `ci.yml` — pull requests and main

| Job | Runs | Fails when |
| --- | --- | --- |
| Build, unit & integration tests | `check-ga-pins.sh`, `gaOnlyCheck`, `check-image-pins.sh`, `:desktop-shared:test`, `:desktop-user:test`, `:desktop-admin:test`, `:server:test`, `:server:integrationTest`, the JavaFX coverage artifact, coverage report and `coverageVerification`, SonarQube when a token is configured | A pre-release pin, a floating image, a failing test, or coverage below the floor in any of the four product modules |
| Database identity pre-flight | `./scripts/test-database-identity.sh` against a throwaway PostgreSQL | Any documented exit status of `check-database-identity.sh` changes, including a rejected password or an unreachable host being reported as a fresh install |
| JavaFX FXML tests | `xvfb-run -a -s "-screen 0 1280x1024x24" ./gradlew :desktop-user:fxTest :desktop-admin:fxTest`, then `:ui-spike:spikeCheck` | FXML or controller wiring breaks, a light/dark or English/Vietnamese golden drifts, the chat timeline misses its render/append/rebuild/resize/pulse-gap budgets (`desktop-user/build/timeline-benchmark.md`), or the AtlantaFX/Ikonli proof of concept stops passing |
| Live user journey (isolated Docker) | `./scripts/live-journey.sh`, then verifies no temporary container, volume or network survived | The end-to-end journey fails, or it leaks Docker resources |
| Linux app image | Builds the desktop packaging output on Linux, then runs `scripts/verify-app-image.sh --image <dir> --launch` on both images | A packaging step fails, an image is missing a jar the app needs, or its launcher exits instead of showing its first window |

The JavaFX job pins Xvfb's screen at 1280x1024, because the toolkit default is 640x480 and the
goldens capture scenes up to 1184x784; pinned, the display is never the limit on a stage.

The shell-state table in `ShellStateFxTest` has an opt-in stress mode:
`JAVA_TOOL_OPTIONS=-Dnexus.shellTable.repeat=600 xvfb-run -a ./gradlew :desktop-user:fxTest
--tests '*ShellStateFxTest' --rerun` runs the table that many times in one JVM, each pass with a
fresh server, socket, window and cache directory. It is a separate test that the property enables,
so the normal pass keeps the suite's 60 second cap and the gate above is unchanged; only the
stress test's own timeout is raised. It exists because the only historic failure of this table was
a ten-second wait in the step loop that external CPU load could not reproduce.

The same mode runs in its own workflow, [`shell-stress.yml`](#shell-stressyml--on-demand), so
triggering it never re-runs the build, the live journey or the packaging.

The same job runs `xvfb-run -a ./gradlew :ui-spike:spikeCheck`, the AtlantaFX/Ikonli proof of
concept. It is a check rather than a demo: it fails on a bundled font that stops loading, a Nexus
token that stops winning the cascade over Primer, contrast below 4.5:1, an unskinned ComboBox or
DatePicker popup, an FXML icon whose pack font is missing, `admin.css` failing to resolve a token,
an AtlantaFX control without a skin, or a `ThemeManager` that stops persisting SYSTEM/LIGHT/DARK.
Its screenshots are uploaded as the `ui-spike` artifact. The module is excluded from Sonar coverage
(`sonar.coverage.exclusions`), so these checks — not a coverage figure — are what keep it honest.

The two desktop modules take their coverage from their JavaFX suites, so the `fx-test` job uploads
their `fxTest.exec` files as the `fx-coverage` artifact and the analysis job downloads it before the
coverage report, the gate and `./gradlew sonar` (`build-test` therefore waits for `fx-test`).
Without that data the module reports were empty, SonarQube counted both UI modules as 0% covered,
and the project sat at 38%. With it they measure 85.12% and 84.78% of lines, and the project reports
74.0% coverage. Across the four product modules and both desktop suites the
aggregate report — the one the bundle floor is checked against — measures 85.56% of lines and 61.11%
of branches, above both bundle floors (83% and 58%). Summing the four per-module reports instead
reads lower, because a module's report only credits the suites it names. The shared module is the one
the difference used to hide the most: the theme manager and the popup theme propagation only run with
a toolkit, so the suites that exercise `com.nexus.desktop.shared/ui` are the two clients' JavaFX
suites. Its own report now reads both clients' `fxTest.exec` files when the artifact is present, so
SonarQube credits that code from the module it lives in and not only from the aggregate. The package
floors are rounded down the same way.

SonarQube measures the same four product modules as `coverageModules`: `ui-spike`, the AtlantaFX
proof of concept no product module references, and `fx-test-support`, the harness the JavaFX suites
run on, are excluded from its coverage through `sonar.coverage.exclusions`. Counting them measured
the tooling rather than the product and held the quality gate at 65% of new code. The shared module's
own report has to include the clients' JavaFX exec data for the same reason: without it, every new
line in `PopupThemes`, `ThemeManager` or `ConfirmDialog` was judged uncovered new code even though
the JavaFX suites run it.

Both `coverageReport` and the gate depend on `coverageDataCheck`, which refuses the measurement when
an exec file is missing, older than the sources of the module it covers, or written by a suite that
did not run all of its tests. The first two catch a stale run: a second
`./gradlew fullTest coverageReport` leaves the suites up-to-date and reports the first run's data,
and the JavaFX exec files arrive as separate artifacts. Timestamps cannot see a partial run, so each
suite is also held to the test classes it compiled: a class with no `TEST-*.xml` report in that
suite's own results means the run was narrowed (`--tests '...'`) or skipped it, and the check names
the suite and the missing classes. Measure a tree with
`xvfb-run -a ./gradlew fullTest coverageReport --rerun-tasks`.

A package average still hides a weak class, so five floors are per class rather than per package:
`OutboxPublisher` (100.00% line / 95.89% branch when the floor was set), `ShellController`
(81.62% / 63.95%), `AdminService` (85.96% / 58.86%), `AdminAppContext` (74.19% / 60.00%) and
`RealtimeConnection` (93.48% / 100.00%). The
publisher's three uncovered branches are guards no publish reaches — an empty mark list, an
exception with no message, an empty attempt list — while its encode-poison and connection-failure
catches are driven by publishing a stubbed MLS row through a mapper that refuses to encode it and a
Redis template that drops the connection on the row-by-row retry. `AdminService` joined them when its
group page gained a `createdAt` cursor and a sort guard the suite can drive.

`ShellController` rose with `ShellStateFxTest`, which drives one shell through a table of states —
open a group, switch to the direct chat and send, delete for this user only, delete for everyone
through its confirmation, decline that confirmation and the one that clears the history and check
nothing changed, clear the history through its confirmation, open a chat from the friends picker,
create a group from it, receive a realtime event for the open chat and then one for the group that is
no longer open, which must repaint nothing. The table then rejoins a group, adds and removes a member
through the two member dialogs, searches for a message that belongs to a direct chat other than the
open one (so the hit's sender name comes from the list) and reports the open chat with a cached
message as evidence. What stays uncovered are the error and recovery paths the table does not drive,
which is why each floor sits a few points under the measured figure instead of at it.

`RealtimeConnection` covers every branch of the retry policy without sleeping: the suite hands the
connection a fake socket and a scheduler that records the delay it asks for, so a failed attempt
waits inside half to whole of a doubling ceiling, an open resets the counter, a close followed by an
error still schedules one retry, a handler or after-connect failure abandons the socket and retries,
and a close refuses an open that arrives after it. Its three uncovered lines are the production
session lookup and the daemon-thread factory, which need a real handshake to reach.

The line figure is reproducible because no suite relies on a timer to reach a line. The presence
heartbeat used to be covered only when a Spring context happened to outlive its 30-second first run,
which moved the server figure by two lines between runs; `AuthenticatedWebSocketHandlerTest` now
drives `refreshPresence()` directly, for open sessions and for an unreachable Redis, and
`RealtimeWithoutOpenConversationFxTest` signs in against a server with no conversations so the shell
really has nothing open when a realtime event arrives. Two `--rerun-tasks` runs now report the same
covered and missed lines and branches.

Every JavaFX suite runs through `fx-test-support`'s `FxThread`, which gives a task a 10 second budget
instead of the 30 each test used to carry, and on expiry throws the JavaFX thread's stack so the test
names the frame it is stuck in. `waitForFx` polls with the time it has left, so a blocked thread makes
it fail at the caller's deadline — carrying that stack as the cause — rather than sitting out the task
budget. Ten seconds is well above the four seconds it takes a cold first task
to load FXML and CSS, and far below the point where a stall is indistinguishable from a slow machine.
Network calls in a task is the failure this catches: `AdminAppContext.signIn` is three HTTP round
trips, and it, the resource bundles and the golden files are loaded on the test thread so an FX task
only builds and lays out what it then asserts on.

`java:S2077` ("Make sure using a dynamically formatted SQL query is safe here") is still ignored for
`server/administration`, through `sonar.issue.ignore.multicriteria` in `build.gradle`.
The admin group list no longer depends on it: `AdminSupport.groupPageSql` returns one of sixteen
constant statements (two sort columns, two directions, with or without a search term, with or without
a cursor) and `AdminGroupSearchPlanIntegrationTest` now plans the statement the service really runs.
The remaining statements in that package still choose their ORDER BY from a whitelist and assemble
optional filters (accounts, reports, audit log, login history, friends, group members, the two
analytics pages), so their text is built at runtime even though every value is a bound parameter.

`server/messaging` and the outbox no longer need theirs either: the chat list, the message history
and both pending outbox polls are constant statements, and `MessagingPlanIntegrationTest` explains
each one against a seeded database (3,000 groups, 20,000 messages, 2,000 outbox rows).
`OutboxPublisherIntegrationTest` covers the fan-out itself: each envelope variant is addressed to the
audience it is meant for — a created or deleted message to every member still in the conversation, a
hidden one only to the person who hid it, MLS ciphertext and commits only to members whose join epoch
covers them. A batch whose Redis publish failed stays unpublished, with its `attempts` counter raised
once per failure and both new meters fed, so the next poll retries it; a row that reaches five failed
publishes is dropped from both polls and counted as abandoned; a failed row waits out its 2 s backoff
instead of coming back on the next tick (and is delivered once it does); a row that cannot be encoded is
abandoned while the rest of its batch is published; and two publishers polling at once never hand
the same row to both. The poll's `SKIP LOCKED` is proven rather than assumed: another transaction
holds the pending rows locked and the poll still returns without publishing them (a plain
`FOR UPDATE` would block and time the assertion out). A mark that fails after the publish went out
re-sends the same event on the next poll, by design.

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
| Coverage floor | `./gradlew coverageVerification` (needs the desktop `fxTest` exec data, so run `fullTest` first) | Silent loss of tested behaviour in any of the four product modules, including the bundle (83% of lines, 58% of branches); the per-package floors are in `build.gradle` |
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

## `shell-stress.yml` — on demand

The state table in `ShellStateFxTest` runs once in the `fx-test` job. This workflow repeats it in a
single JVM, each pass with a fresh server, socket, window and cache directory, because the only
historic failure of that table was a ten second wait in the step loop that external CPU load could
not reproduce. It holds nothing else, so triggering it never re-runs the build, the live journey or
the packaging.

| Job | Runs | Fails when |
| --- | --- | --- |
| Shell-state stress (on demand) | `xvfb-run -a -s "-screen 0 1280x1024x24" ./gradlew --no-daemon :desktop-user:fxTest --tests '*ShellStateFxTest' --rerun` with `JAVA_TOOL_OPTIONS=-Dnexus.shellTable.repeat=<shell_repeat>`, 600 passes unless the input says otherwise | Any pass of the state table fails; the surefire XML and the HTML report are uploaded as `shell-stress-reports` either way |

Start it from the Actions tab (**Run workflow**, optionally lowering `shell_repeat`) or with
`gh workflow run shell-stress.yml --ref <branch> -f shell_repeat=600`. GitHub only lists and
dispatches a workflow whose file exists on the default branch, so until this one is merged the
dispatch answers `HTTP 404: workflow shell-stress.yml not found on the default branch` even with a
`--ref` to the branch that has it. It uploads the reports even
when it fails, so the next real failure keeps the iteration number, the step that did not settle, the
shell diagnostics and the FX-thread responsiveness that the failure message carries — the evidence
the historic failure lost. At 600 passes it took 18 m 36 s locally; a `shell_repeat=20` run took
2 min 18 s on GitHub's runner, so the job's budget is 60 minutes instead of the 20 the main JavaFX
job uses. Its own concurrency group means a push that cancels an in-progress CI run leaves a stress
run alone.

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
