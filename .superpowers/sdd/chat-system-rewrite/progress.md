# SDD ledger — plan: docs/plan.md

## Scope & Constraints
- Baseline: Java 27, Gradle 9.8.2, JavaFX 27, Spring Boot 4.1.2.
- Clean cutover: Flyway migrations under server/src/main/resources/db/migration.
- All Project.pdf requirements for Admin and User apps must be completely satisfied.
- Ponytail full: minimal clean diffs, zero over-engineering, standard libraries first.

## Phases
- [~] Phase 1: Group Messaging & MLS-Style End-to-End Encryption (RFC 9420 conformance open)
  - [x] Server MLS routing: epoch compare-and-set commits, idempotent application ciphertext,
        per-member recovery packages, history filtered by join epoch, realtime fan-out,
        plaintext group messages rejected on the direct-message endpoints.
  - [x] Client crypto layer on Bouncy Castle 1.86 primitives: signed key packages, signed
        add/remove commits with per-recipient welcomes, sender-signed per-epoch AES-GCM
        messages, roster key-package verification, join-epoch binding, replay rejection,
        encrypted local state and a user-held recovery package
        (`desktop-shared/src/main/java/.../shared/mls`, 9 unit tests).
  - [ ] RFC 9420 wire compatibility: `bcmls 1.86` exposes only the low-level `Group` state machine
        and has no key-package factory or serializable client state, so this layer is MLS-style,
        not RFC 9420. Groups must not be described as RFC 9420 E2EE.
  - [x] Wired the crypto layer into the user desktop group flow: create group with key-package
        exchange, add/remove member commits, signed ciphertext upload, decrypted local history,
        realtime MLS commit/message handling, and a recovery-code dialog backed by encrypted
        per-group snapshots. Key packages are published on login through the new server
        directory (`PUT/GET /api/v1/users/.../key-package`, Flyway V6).
  - [ ] Full RFC 9420 client assessment (2026-11-06): defer. `bcmls 1.86` exposes a usable
        low-level `Group`/TreeKEM/codec core and its upstream tests exercise the MLS interop
        vector families, but there is no KeyPackage factory and, critically, no Group state
        export/import. Viable persistence is replay from KeyPackage private material + Welcome
        + an ordered commit log (server must retain commits since join). 1.85's `mls.client`
        package was only a gRPC interop harness and 1.86 removes it; 1.85 is affected by
        CVE-2026-17507, so downgrading is not an option. Estimated 6-9 engineer-weeks for a
        production client plus interop/security review.
- [~] Phase 2: Complete Admin Workflows & Analytics (Project.pdf sections 1-9)
  - [x] Server APIs: account CRUD, lock/unlock, admin password reset, per-user and global login history,
        per-user friend lists, group list/member inspection, report filtering + decisions with locking,
        registration range lists, friend/friend-of-friend analytics, activity analytics,
        monthly registration and app-open charts, audit event listing.
  - [x] Flyway V5: contract-aligned report statuses; audit/moderation references survive account deletion.
  - [x] Coverage: 22 PostgreSQL integration tests in IdentityMigrationIntegrationTest plus
        AdminService/AdminController and MLS unit tests.
  - [x] Admin workspace migrated to FXML + controllers: login, shell/navigation, accounts,
        reports, groups/members, registrations, analytics and audit, with loading/empty/error states.
- [x] Phase 3: Distributed Presence (Redis-backed online status; friends dialog only, not the full social UI)
  - [x] Redis presence tracks connection IDs in a per-user sorted set with a TTL and
        heartbeat, so multiple clients/instances keep a user online until the last one leaves.
  - [x] WebSocket connect/disconnect and scheduled heartbeat update presence; friend list,
        search, incoming requests and `/users/me` decorate responses with online/offline.
  - [x] User desktop Friends dialog shows live server presence and opens a direct chat.
  - [x] Tests: `RedisPresenceStore` via Redis Testcontainers, social presence mapping,
        WebSocket presence lifecycle; unit and integration suites green.
- [~] Phase 4: Desktop Apps Completion (User & Admin JavaFX UI)
  - [x] **Task 7 user frontend migration (2026-11-07).** The user desktop is now a thin
        `Application` launcher over FXML views (`login.fxml`, `shell.fxml`, `user-picker.fxml`)
        and controllers with an explicit composition root (`UserAppContext`). Session, cache,
        MLS and network state are owned per signed-in account; no static session.
  - [x] Shared design system in `desktop-shared/.../shared/ui`: `Theme`/`ThemeManager`
        (light/dark/system with persisted preference), `I18n` ResourceBundle wrapper,
        `UiComponents` (avatars, presence dots, person rows, loading/empty/error states) and
        a semantic-token stylesheet (`theme.css`) with no screen-level inline colours.
  - [x] Vietnamese and English bundles, UTF-8; `-Dchat.locale=vi` overrides the OS locale.
        Test-critical automation labels remain stable across locales.
  - [x] User shell retains conversation selection, direct/group messaging, message search,
        friends, group creation/member add/remove, recovery code, theme toggle, sign-out,
        pending/retry state, and realtime MLS handling through the new controllers.
  - [x] Accessibility baseline: label-to-field association, accessible names for unlabeled
        inputs and icon buttons, focus traversal, default actions, and decorative presence
        marks removed from focus.
  - [x] **Task 7 admin frontend migration (2026-11-07).** `desktop-admin` is now a thin
        `Application` launcher over FXML (`login.fxml`, `shell.fxml`, `accounts.fxml`,
        `reports.fxml`, `groups.fxml`, `registrations.fxml`, `analytics.fxml`, `audit.fxml`)
        with one controller per screen. `AdminAppContext` is the composition root: it owns
        the signed-in session, `MyProfile`, and one virtual-thread executor; there is no static
        session and every API call runs off the FX thread.
  - [x] Admin uses the shared `Theme`/`ThemeManager`, `I18n`, `UiComponents` and `theme.css`,
        plus an admin-only `admin.css` that consumes theme tokens only. Admin bundles cover
        Vietnamese and English; no FXML inline styles or hardcoded admin UI strings remain.
  - [x] Admin accessibility baseline: label-to-field associations, accessible action names,
        table accessible names, default sign-in action, focus traversal and theme toggle.
  - [x] Added `AdminDesignSystemFxTest`, retained `AdminDesktopApplicationFxTest`, and added
        `AdminInteractionFxTest` backed by `FakeAdminServer`: login, account lock/unlock,
        report uphold, groups/members, registrations, analytics charts/activity/friend report,
        audit listing, and sign-out.
  - [x] Added shared confirmation boxes for delete, lock/unlock, password reset and report
        decisions. Interaction tests cover both cancel and confirm paths.
  - [x] Added `AdminGoldenScreenshotFxTest` with four reviewed images (Accounts/Reports in
        light and dark at 1180x780) and a 0.75% pixel-difference tolerance; layout and
        dark-theme contrast fixes cover wrapping actions, table columns, DatePicker, ComboBox
        and CheckBox.
  - [x] Added opt-in `RealServerAdminFlowFxTest`; it creates isolated users, a friendship and
        reported evidence, then exercises the admin FXML shell against the real server.
  - [x] **User flows completion (2026-11-09).** registration, reset/change password, profile,
        discovery, requests/accept/decline, outgoing cancel, unfriend, block/unblock with
        blocked list, report with selected evidence, delete-for-me and clear history, and group
        rename/promote/demote/remove/leave are reachable through FXML controllers. Destructive
        actions use the shared `ConfirmDialog` with cancel and confirm paths covered by tests.
  - [x] **Admin workflow completion (2026-11-07).** Accounts now supports create, edit,
        delete with inline confirm/cancel, lock/unlock, admin password reset, per-user login
        history and per-user friend list; Reports shows submitted evidence and decision actions.
        The fake-loopback interaction test executes create -> edit -> reset -> history/friends ->
        delete confirm/cancel -> report evidence -> sign-out. Admin `test` now has a static
        resource/design contract instead of NO-SOURCE.
  - [x] Added `fxTest` source sets/tasks for desktop-user and desktop-admin. Login scenes render
        on the host X11 display, accept typed credentials and produce a scene snapshot in tests.
  - [x] Added a desktop-user interaction test backed by a loopback fake chat API: sign in,
        conversation list, select the conversation and send a direct message.
  - [x] Added a group/MLS/realtime JavaFX test: opens a local MLS group, sends an encrypted
        group message through the UI, asserts the server only receives ciphertext, then pushes
        a live `MlsMessage` event over a real loopback WebSocket and asserts the decrypted text.
  - [x] Fixed the generated SDK's binary request bodies: `File` bodies were serialized as a JSON
        path string; `client-sdk` now patches those four binary methods to upload raw file bytes
        (key package, commit, group message and recovery package).
  - [x] Added an opt-in `RealServerUserFlowFxTest` for a live local server. It signs in through
        JavaFX, opens a direct conversation, sends a message through the UI and verifies through
        a separate real SDK session that the message was persisted. The test is skipped unless
        `CHAT_REAL_SERVER=true` is set.
  - [x] Added an opt-in `RealServerGroupMlsRealtimeFxTest`: Alice creates an MLS group through
        the real service, Bob publishes a key package and joins from the server commit, Alice
        sends through JavaFX, and Bob receives/decrypts the live `MlsMessage` over a real
        WebSocket connection.
  - [x] Fixed the session cookie path from `/api/v1` to `/`, so browsers and WebSocket clients
        send the session to `/ws/v1` as well as to the REST API.
  - [x] Fixed MLS application-message fan-out: `JdbcMlsRepository.sendMessage` now inserts a
        `message_outbox` row, so realtime `MlsMessage` events are published to group members.
  - [x] Fixed the server Docker build: the Dockerfile now copies the desktop modules required by
        the multi-project Gradle settings.
- [ ] Phase 5: Verification, Packaging & Quality Gate
  - [ ] No repeatable 10,000-session / 100 msg/s load test, so the p95 delivery target is unproven.
  - [~] Frontend quality gate: both desktop apps use FXML, shared theme tokens, i18n, the
        complete Project.pdf user/admin flow set and light/dark goldens. Static bundle, inline
        style, CSS colour and Java literal audits are clean. The automated WCAG 2.2 AA audit
        passes; manual screen-reader validation, end-to-end keyboard-only review,
        startup/interaction/scroll benchmarks and packaging remain open.
  - [ ] Cross-platform jpackage launch, signing and notarization are not verified.

## Verification log (newest first)

- **2026-11-09 (CI live journey gate).**
  - Added the `Live user journey (isolated Docker)` job to `.github/workflows/ci.yml`.
    It checks out the repository and runs `./scripts/live-journey.sh` on `ubuntu-latest`,
    with no JDK setup, Xvfb, Compose stack, admin secrets or pre-existing database. The job
    uploads the desktop-user JavaFX reports and then fails if any `chat-system-live-*`
    container, network or volume remains.
  - Updated `docs/CI-CD.md`: workflow responsibilities, local note, required branch checks
    and the verification table now include the isolated live journey.
  - Validation performed here: PyYAML parsed the workflow and assertions confirmed the job,
    command, artifact upload and cleanup step. GitHub Actions execution was not run in this
    environment; the local zero-config runner was already verified end to end.
  - Installed `actionlint` 1.7.12 from the official release archive (SHA-256 verified) and ran
    it against `.github/workflows/ci.yml`: zero findings. No workflow fix was needed.
  - Extracted the workflow's cleanup step verbatim and proved both outcomes: with a stopped
    `chat-system-live-*` container, network and volume present it exited 1 and printed all
    three names; after removing them it exited 0. The primary stack stayed healthy (`200`).
  - No commit, push, PR, merge or deploy was performed.

- **2026-11-09 (zero-config live journey runner).**
  - Rewrote `scripts/live-journey.sh` so it needs only Docker plus network access: it builds
    the server image and a derived test-runner image, creates a unique network, starts
    throwaway PostgreSQL, Redis and server containers, registers a temporary admin through
    the real CSRF-protected API, promotes that account in the throwaway database, and runs
    `RealServerUserJourneyFxTest` headlessly. Generated admin credentials are the default;
    `CHAT_REAL_ADMIN_USERNAME` / `CHAT_REAL_ADMIN_PASSWORD` still override them.
  - Added `scripts/live-journey-test.Dockerfile` to install `libpangoft2-1.0-0`. The stock
    `gradle:9.8.2-jdk27` image extracts JavaFX's `libjavafx_font_pango.so` but lacks its
    system Pango runtime, which made text-bearing JavaFX tests fail before this change.
  - Headless JVM options are `-Dglass.platform=headless -Dprism.order=sw
    -Djava.awt.headless=true -Djavafx.cachedir=/tmp/javafx-cache`; Gradle runs with
    `--rerun-tasks` so a reused cache cannot report the opt-in test as up to date.
  - Final zero-config run with `DISPLAY` and all admin/cache/image variables unset:
    exit 0; JUnit XML `tests="1" failures="0" errors="0"`; `12 actionable tasks: 12 executed`.
    No leftover `chat-system-live-*` containers, networks or volumes, no temporary Gradle
    directory, no Xvfb process; the primary Compose stack stayed healthy (`200`).
  - Explicit admin override run with an isolated temporary cache: exit 0; live journey 1/1;
    cleanup verified. The documented override path is therefore exercised, not just declared.
  - Forced post-startup failure with `CHAT_LIVE_TEST_IMAGE=postgres:18-alpine ./scripts/live-journey.sh
    --skip-build` stopped at the test step only: exit 1, no leftover containers/networks/
    volumes/temp cache/Xvfb, primary stack still healthy (`200`).
  - Foreground interruption after startup (`timeout --preserve-status -s INT 20
    ./scripts/live-journey.sh --skip-build`) exited 130; the same cleanup audit was empty
    and the primary stack stayed healthy (`200`).
  - `bash -n` and ShellCheck pass. README and `--help` describe Docker/network prerequisites,
    auto-provisioning, the test-runner image, overrides and the reusable-cache caveat.
    Reusing an active host `~/.gradle` can contend on Gradle's cache lock; the default
    temporary cache avoids that and is documented as the normal path.
  - Reusable `chat-system-live-server:latest` and `chat-system-live-test:latest` images and
    the Docker build cache remain; this entry supersedes the earlier live-journey notes.
  - No commit, push, PR, merge or deploy was performed.

- **2026-11-09 (user account/social flow proof + live journey).**
  - Added FXML/controller coverage for registration followed by login with the new account,
    duplicate registration, a wrong current password, and a friend request to an already
    blocked user. The loopback API double now mirrors the server's 401 and 409 responses.
  - Added specific EN/VI status messages for a wrong current password and a blocked/conflicting
    friend request; no new strings are hardcoded in UI code.
  - Exact clean-state gate: `DISPLAY=:0 ./gradlew :server:test :desktop-shared:test
    :desktop-admin:compileJava :desktop-admin:test :desktop-admin:fxTest :desktop-user:test
    :desktop-user:fxTest --continue` -> BUILD SUCCESSFUL (exit 0) in 36s.
  - JUnit XML: server 44/44; desktop-shared 27/27; desktop-admin 9 tests with 1 skipped;
    desktop-user 20 tests with 3 skipped. Failures/errors: 0.
  - Static audit: user 304 used / EN 313 / VI 313 and admin 199 / EN 257 / VI 257, parity 0;
    desktop inline FXML styles 0; desktop CSS hex colours 0; desktop main Java UI literal
    files 0.
  - Hardened `AdminInteractionFxTest` to wait for the post-save refreshed selection before
    opening the reset-password confirmation, removing a reload race found in the combined gate;
    three consecutive isolated reruns passed.
  - Opt-in `RealServerUserJourneyFxTest` passed 1/1 against an isolated HTTP-cookie server on
    port 18081 using the existing PostgreSQL + Redis stack. It now also asserts duplicate
    registration returns 409, a wrong current password returns 401, and a blocked-user friend
    request returns 409. The temporary container was stopped; the normal server on 8080
    remains healthy with `CHAT_SECURE_COOKIE=true`.
  - Added `scripts/live-journey.sh`: it builds the server image, starts a throwaway Redis and
    server on an ephemeral loopback port, waits for both, runs the opt-in journey, and traps
    teardown. Verified `bash -n` and ShellCheck; the happy path passed 1/1 with no leftover
    containers, and a forced JVM startup failure exited nonzero with both temporary containers
    removed and the primary stack still healthy. README documents the admin environment variables.
  - No commit, push, PR, merge or deploy was performed.

- **2026-11-09 (WCAG 2.2 AA automated audit).**
  - Fixed confirmed light/dark contrast failures in `theme.css`: muted/subtle text and
    accent-on-accent-soft now meet 4.5:1; strong borders and the focus ring meet 3:1.
    `ThemeContrastTest` asserts every text and non-text token pair in both themes.
  - Added semantic focus-ring effects to buttons, check boxes, combo/date pickers, lists,
    tables and selected account tabs; check boxes now meet 24x24 targets and input/list/table
    boundaries remain visible. `DesignSystemFxTest` asserts 24x24 targets and visible focus.
  - Added accessible names to unlabeled user/admin inputs, lists, tables and charts without
    new bundle strings; `ConfirmDialog` and admin inline confirmations capture and restore
    focus. Assertions were added to the user/admin flow tests.
  - User and admin goldens were regenerated after clearing focus and moving theme changes
    onto the FX thread, so light/dark captures are deterministic. All 14 images compare within
    the 0.75% tolerance and channel delta 16.
  - Exact clean-state gate: `DISPLAY=:0 ./gradlew :server:test :desktop-shared:test
    :desktop-admin:compileJava :desktop-admin:test :desktop-admin:fxTest :desktop-user:test
    :desktop-user:fxTest --continue` -> BUILD SUCCESSFUL (exit 0) in 32s.
  - JUnit XML: server 44/44; desktop-shared 27/27; desktop-admin 9 tests with 1 skipped;
    desktop-user 19 tests with 3 skipped. Failures/errors: 0.
  - Static audit: user EN 311 / VI 311 and admin EN 257 / VI 257, parity 0; desktop inline
    FXML styles 0; desktop CSS hex colours 0; desktop main Java UI literal files 0.
  - Still open: manual screen-reader validation (Windows/macOS, best-effort Linux), end-to-end
    keyboard-only review, startup/interaction/10,000-message scroll budgets, and packaging/
    signing. These automated checks do not prove screen-reader semantics or measured
    performance. No commit, push, PR, merge or deploy was performed.

- **2026-11-09 (user workflow completion + full gate).**
  - User app now has FXML/controller screens for registration, password reset, account
    (profile, security, discover, requests, friends, blocked), report-with-evidence, group
    settings and the shell extensions for delete-message/clear-history. All destructive paths
    use the shared `ConfirmDialog`.
  - Exact clean-state gate (stale `*/build/test-results` deleted first):
    `DISPLAY=:0 ./gradlew :server:test :desktop-shared:test :desktop-admin:compileJava
    :desktop-admin:test :desktop-admin:fxTest :desktop-user:test :desktop-user:fxTest
    --continue` -> BUILD SUCCESSFUL (exit 0) in 39s.
  - JUnit XML: server 44/44; desktop-shared 25/25; desktop-admin unit 3/3 and `fxTest`
    6 tests with 1 opt-in real-server test skipped; desktop-user unit 4/4 and `fxTest`
    14 tests with 3 opt-in real-server tests skipped. Failures/errors: 0.
  - `DISPLAY=:0 CHAT_REAL_SERVER=true CHAT_REAL_ADMIN_USERNAME=admin2
    CHAT_REAL_ADMIN_PASSWORD='admin password 12345' ./gradlew :desktop-user:fxTest --tests
    '*RealServerUserJourneyFxTest*'` passed 1/1 against the local PostgreSQL 18 + Redis +
    server stack. It drove registration, friendship request/accept, message, report evidence,
    block cancel/confirm, unblock, unfriend cancel/confirm, profile edit, password change,
    group create/rename/promote/leave cancel/confirm, then deleted its test accounts. The
    running server was returned to `CHAT_SECURE_COOKIE=true` afterwards.
  - Goldens: 10 user images at 960x720 (account), 560x780 (register), 540x640 (reset),
    560x620 (report) and 640x600 (group settings) in light/dark; 4 admin images at 1180x780.
    Comparison tolerance is 0.75% differing pixels with channel delta 16. The report golden
    now uses a fixed instant so it cannot drift with the clock.
  - Static audit: user 302 used keys / EN 311 / VI 311 and admin 199 / EN 257 / VI 257;
    missing EN/VI 0; parity differences 0; inline FXML styles 0; hex colours in desktop CSS 0
    (theme tokens use equivalent `rgb()` values); hardcoded Java UI literal files 0.
  - Still open: full WCAG 2.2 contrast/screen-reader validation, startup/interaction/10,000-
    message scroll performance budgets, and cross-platform packaging/signing. No commit,
    push, PR, merge or deploy was performed.

- **2026-11-09 (admin confirmations, goldens, real-server smoke).**
  - Added shared confirmation UI for delete, lock/unlock, password reset and report
    uphold/dismiss. `AdminInteractionFxTest` covers cancel and confirm for lock, reset, delete
    and report decisions.
  - Added `AdminGoldenScreenshotFxTest`. Four reviewed PNGs at 1180x780 cover Accounts and
    Reports in light/dark; comparison uses 0.75% different-pixel tolerance with channel
    delta >16. Layout/CSS fixes removed clipped table actions, clarified dark-theme
    DatePicker/CheckBox/ComboBox contrast and shortened the save label so the action row fits.
  - `CHAT_REAL_SERVER=true ... ./gradlew :desktop-admin:fxTest --tests
    '*RealServerAdminFlowFxTest*'` passed 1/1 against the local PostgreSQL 18 + Redis + server
    stack. The test creates isolated users, a friendship and reported evidence, then drives
    login, edit, lock cancel/confirm, unlock, reset cancel/confirm, create/delete, report
    evidence and uphold cancel/confirm through the real FXML shell. The local server was
    started with `CHAT_SECURE_COOKIE=false` because Java HttpClient will not send Secure
    cookies over plain HTTP; production defaults remain unchanged.
  - Exact gate after deleting stale test-results:
    `DISPLAY=:0 ./gradlew :server:test :desktop-shared:test :desktop-admin:compileJava
    :desktop-admin:test :desktop-admin:fxTest :desktop-user:test :desktop-user:fxTest
    --continue` -> BUILD SUCCESSFUL (exit 0) in 1m 8s.
  - JUnit XML: server 42/42; desktop-shared 25/25; desktop-admin `test` 3/3 and `fxTest`
    6 tests with 1 opt-in real-server test skipped; desktop-user `test` 4/4 and `fxTest`
    8 tests with 2 opt-in real-server tests skipped. Failures/errors: 0.
  - Static audit: 199 admin keys referenced from FXML/direct `i18n.get|format`; EN 257 and
    VI 257 defined; missing EN 0, missing VI 0; bundle parity equal; inline FXML styles 0;
    hex colours in `admin.css` 0; Java UI literal files 0.
  - Test-only accounts were deleted from the local database after the run.
  - Still open: user-app flow completeness, full WCAG 2.2 contrast/screen-reader audit,
    startup/interaction/scroll benchmarks, repository-wide hardcoded-string check and
    packaging/signing. No commit, push, PR, merge or deploy was performed.

- **2026-11-07 (admin workflow completion + full gate).**
  - Exact gate after deleting stale test-results: `./gradlew :server:test :desktop-shared:test
    :desktop-admin:compileJava :desktop-admin:test :desktop-admin:fxTest :desktop-user:test
    :desktop-user:fxTest --continue` -> BUILD SUCCESSFUL (exit 0) in 48s.
  - JUnit XML: server 42/42; desktop-shared 25/25; desktop-admin `test` 3/3
    (`AdminResourceContractTest`) and `fxTest` 4/4 (`AdminDesignSystemFxTest` 2,
    `AdminDesktopApplicationFxTest` 1, `AdminInteractionFxTest` 1); desktop-user `test` 4/4 and
    `fxTest` 8 tests with 2 opt-in real-server tests skipped. Total failures/errors: 0.
  - Admin interaction flow executed through FXML + fake loopback API: login, lock/unlock,
    create `carol`, edit full name, request password reset, load login history and friends,
    destructive delete cancel then confirm, open report evidence, uphold report, groups/members,
    registrations, analytics, audit and sign-out.
  - Design contract: `AdminDesignSystemFxTest` checks the new Accounts/Reports screens for
    label associations, focus traversal, default save action, accessible table names, button
    names and EN/VI key presence. `AdminResourceContractTest` scans every FXML `%key` and Java
    `admin.*` key against both bundles and rejects inline FXML style, FXML literal text, hex
    colours in `admin.css`, and Java UI literals.
  - Static audit output: 247 used admin keys; EN 247, VI 247; missing EN 0, missing VI 0;
    bundle key parity 0 differences; inline FXML style 0; hardcoded FXML text 0;
    `admin.css` hex colours 0; Java UI literal files 0.
  - Still open: user-app flow completeness, golden screenshots in both themes, full WCAG 2.2
    contrast/screen-reader audit, startup/interaction/scroll benchmarks, and packaging/signing.
    No commit, push, PR, merge or deploy was performed.

- **2026-11-07 (Task 7 admin frontend migration).**
  - Exact gate: `./gradlew :desktop-admin:compileJava :desktop-admin:test :desktop-admin:fxTest
    :desktop-shared:test :desktop-user:test :desktop-user:fxTest --continue` ->
    BUILD SUCCESSFUL (exit 0) in 16s. `desktop-admin:test` is NO-SOURCE.
  - XML counts: admin fxTest 3/3 passed (0 skipped, 0 failures/errors); shared test 25/25;
    user test 4/4; user fxTest 8 tests, 6 passed and the 2 opt-in real-server tests skipped.
  - Admin fxTest coverage: light/dark theme token application, Vietnamese FXML loading,
    label associations, focus traversal, default sign-in action, login smoke, and a full
    fake-server interaction flow through accounts lock/unlock, reports uphold, groups/members,
    registrations, analytics charts/activity/friend report, audit and sign-out.
  - Static audit: 139 admin resource keys used, all present in both EN and VI bundles;
    zero inline FXML styles, zero hex colours in `admin.css`, zero hardcoded admin UI literals.
  - At that point golden screenshots, full WCAG 2.2 contrast/screen-reader audit, performance
    budgets and the admin product flows above were not verified. The admin product flows were
    completed in the later 2026-11-07 admin workflow-completion entry.

- **2026-11-07 (Task 7 user frontend migration).**
  - `./gradlew :desktop-user:test :desktop-user:fxTest :desktop-shared:test --continue` ->
    BUILD SUCCESSFUL. `desktop-user` unit tests 4/4 (`I18nTest`); `desktop-user` fxTest 8
    discovered, 6 passed and the 2 opt-in real-server tests skipped; `desktop-shared` 25/25.
  - `./gradlew :desktop-user:compileJava` -> BUILD SUCCESSFUL.
  - `DesignSystemFxTest` verifies light/dark token application, shared component state
    variants, FXML loading under the Vietnamese bundle, label associations, focus traversal
    and the default sign-in action. `I18nTest` verifies bundle parity, Vietnamese copy,
    missing-key fallback and every `%key` referenced by the three FXML views.
  - Fixed `client-sdk:patchGeneratedBinaryBodies` to be idempotent; a second build no longer
    fails after the generated `DefaultApi.java` has already been patched.
  - User desktop now has `main()` for `./gradlew :desktop-user:run`, FXML login/shell/picker,
    design tokens, theme persistence, loading/empty/error states and accessible controls.
  - Still not verified: admin FXML/design-system migration, golden screenshots, full WCAG 2.2
    audit, startup/scroll performance budgets and the missing user/admin product flows above.

- **2026-11-07 (documentation-only audit pass).**
  - `GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew --no-daemon :server:test :desktop-shared:test :desktop-user:compileJava :desktop-admin:compileJava` -> BUILD SUCCESSFUL in 2m 11s. The test tasks were UP-TO-DATE; XML artifacts show server 42/42, desktop-shared 25/25, desktop-user fxTest 5 tests with 2 skipped, desktop-admin fxTest 1/1.
  - `server/build/test-results/integrationTest` currently holds **one** targeted test, not the 24 recorded below. The 24-test run predates this pass and is not reproducible from current artifacts without rerunning the full integration suite.
  - Code inspection: `desktop-user` is 699 lines and `desktop-admin` 839 lines, each one `Application` class; the desktop modules contain zero `.css` and `.fxml` resources. The user UI only calls listConversations, listFriends, listGroupMembers, createDirectConversation and logout. The admin UI calls adminListUsers, adminSetUserLock, adminListReports, adminDecideReport, adminListGroups, adminListGroupMembers, adminListRegistrations, monthly/activity/friend analytics and audit; it has no account CRUD, login history, per-user friend list or evidence view.
  - No load-test source was found.

### 2026-11-06 and earlier (historical; see caveats above)
- `./gradlew :server:test` — 42 tests pass, including presence mapping and WebSocket
  presence lifecycle, plus the existing MLS/key-package guards.
- 2026-11-06: `./gradlew :server:integrationTest --tests '*IdentityMigrationIntegrationTest'` — 24
  integration tests passed (PostgreSQL 18 + Redis Testcontainers), including the V6
  `mls_key_package` table and multi-connection Redis presence. **Not reproduced on 2026-11-07**:
  the current artifact holds only one targeted integration test.
- `./gradlew :server:integrationTest ... :client-sdk:build` — generated SDK rebuilds from
  api/v1/openapi.yaml with MLS routes, epoch header, key-package routes and recovery-package GET.
- `./gradlew :desktop-shared:test` — 25 tests pass, including direct-device-key state
  persistence, recovery-package round trips, an explicit realtime WebSocket endpoint override,
  and an in-memory client-sync flow that covers create, join, send/receive, add/remove,
  restore-from-snapshot, and realtime `MlsCommit`/`MlsMessage` routing with direct-event
  rejection (`MlsGroupSyncServiceTest`).
- `:client-sdk:clean :client-sdk:compileJava` — regenerates the SDK and applies the binary-body
  patch to all 4 generated File upload methods successfully.
- `DISPLAY=:0 ./gradlew :desktop-user:fxTest :desktop-admin:fxTest` — 5 JavaFX tests are
  discovered; the 3 default tests pass and the two real-server tests skip without
  `CHAT_REAL_SERVER=true`. `:desktop-user:build` and `:desktop-admin:fxTest` also pass.
- `./gradlew :server:test` — 42 tests pass, including the session-cookie path regression.
  The targeted PostgreSQL integration test for MLS application messages passes with the new
  outbox assertion.
- Live two-account group smoke passed: Alice creates `Real Team ...`, Bob joins, Alice sends
  through JavaFX, Bob receives the realtime event and decrypts it, and the server row has a
  published outbox event. The opt-in `RealServerGroupMlsRealtimeFxTest` passes 1/1 with 0 skips.
- Live local smoke (Docker PostgreSQL 18 + Redis + server on `127.0.0.1:8080`): registered
  `alice_real`/`bob_real`, made them friends, created a direct conversation and persisted
  messages. `CHAT_REAL_SERVER=true ... ./gradlew :desktop-user:fxTest --tests
  '*RealServerUserFlowFxTest'` passed: JavaFX login, conversation selection, UI send, and a
  separate authenticated SDK read confirmed `real-javafx-...` on the server.
- `docker compose up -d --build server` now succeeds after the Dockerfile fix, and the Compose
  `server` container is healthy on `127.0.0.1:8080`. Mail health is disabled by default because
  SMTP is optional.
- A live two-account group exchange and WebSocket reconnect/fan-out are still unverified; the
  fake WebSocket test server covers one event per connection. Recovery code is derived from the
  password-derived cache key, so changing the account password invalidates older local state
  and recovery packages.
