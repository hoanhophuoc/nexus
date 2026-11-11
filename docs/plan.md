# Production Chat System Rewrite Implementation Plan

> For agentic workers: Use superpowers:executing-plans and implement one task at a time. Do not discard or overwrite the existing modified workspace.
>
> Status lives in `.superpowers/sdd/chat-system-rewrite/progress.md`; neither this plan nor that ledger is evidence of completion. Verify the current source, migrations, tests and runtime evidence before claiming a task is done.

Goal: Replace the current chat system with a production-grade, user-oriented, accessible and visually polished cross-platform JavaFX user and admin application backed by a secure, horizontally scalable server that covers every feature in Project.pdf.

Architecture: A Spring Boot modular monolith will serve versioned REST operations and secure WebSocket events. Separate JavaFX user and admin apps will use generated API contracts; PostgreSQL stores application data, while Redis supports distributed presence and event fan-out. Group messages use MLS-style end-to-end encryption on Bouncy Castle primitives; RFC 9420 conformance is an open release blocker, so docs and UI must not claim RFC 9420. Message search runs against a locally encrypted history index.

Tech Stack: Java 27, Gradle 9.9.1, JavaFX 27.0.1, Spring Boot 4.1.2, PostgreSQL 18, Redis 8.10, Flyway 13.12.1, Spring Security, Bouncy Castle MLS, JUnit Jupiter, Testcontainers, JavaFX UI tests, JaCoCo, GitHub Actions. Performance tooling: JFR/JDK Mission Control, async-profiler, JMH, k6, JMeter, OWASP ZAP, Eclipse MAT, Toxiproxy, Micrometer/Prometheus/Grafana (Task 9).

Spec: Project.pdf plus the approved design decisions in this conversation.

## Global Constraints

- Java 27 is the selected baseline and LTS status is not a selection criterion. Every dependency, Gradle plugin, GitHub Action and container image uses the newest published release under the Version Currency Policy below.
- Keep the product within the declaration: no voice/video, mobile app, channels, or unrelated Telegram features.
- Treat the existing schema as a clean cutover: create a new versioned schema and make no legacy-data compatibility promise. Back up the old database and leave it intact.
- Protect the current modified and untracked workspace before implementation; inspect changes and preserve them in a checkpoint before replacing files.
- Support 10,000 concurrent client connections and 100 messages/second in the production-like load test, with online message delivery below one second at p95.
- Group encryption targets RFC 9420 MLS; the current implementation is MLS-style and must be described that way. New members can read messages from their join epoch onward; removed members cannot decrypt later epochs. RFC 9420
- End-to-end encryption applies to group messages. Direct messages use TLS and server-side storage protection.
- Search all synchronized history locally using an encrypted index. The server must not receive plaintext search terms.
- Admins may review only report metadata and message evidence explicitly selected and submitted by the reporting user.
- Users can delete messages for themselves; only the sender can delete a message for everyone. Clearing history affects only the requesting user.

## Version Currency Policy

Every dependency, Gradle plugin, GitHub Action and container image uses the **newest GA release**. Pre-releases — alpha, beta, release candidate, milestone, early-access and snapshot builds — are excluded, because their API can change before GA and this project ships as production software. Long-term support is not a selection criterion either: a newer GA line wins over an older LTS line.

The owner briefly asked for the newest build regardless of channel on 2026-11-11 and withdrew that on 2026-11-12, so the five pre-release pins were reverted to their newest GA counterparts, each re-verified with `fullTest`: Spring Boot 4.1.2 (4.2.0 is RC only), JavaFX 27.0.1 (28 is early access), AssertJ 3.27.7 (4.0.0 is a milestone), SLF4J 2.0.20 (2.1.0 is alpha) and Lettuce 7.9.0.RELEASE (8.0.0 is beta).

Verified against `repo1.maven.org` metadata, the Gradle Plugin Portal and Docker Hub on 2026-11-10:

| Component | Pinned now | Newest release | Action |
| --- | --- | --- | --- |
| Java | 27.0.1 | 27.0.1 | current |
| Gradle | 9.9.1 | 9.9.1 | current (wrapper and SHA-256 updated 2026-11-11) |
| Spring Boot | 4.1.2 | 4.1.2 | newest GA release; 4.2.0 exists only as a release candidate |
| JavaFX | 27.0.1 | 27.0.1 | newest GA release; 28 exists only as early access |
| Testcontainers | 2.0.5 | 2.0.5 | current; artifacts are `testcontainers-postgresql` and `testcontainers-junit-jupiter`, `PostgreSQLContainer` moved to `org.testcontainers.postgresql` |
| Jackson | 3.3.0 | 3.3.0 | current; also declared in `server` because the Boot BOM still manages 3.1.7 |
| Flyway | 13.12.1 | 13.12.1 | current; declared explicitly because the Boot 4.1.2 BOM still manages 12.4.0 |
| Lettuce | 7.9.0.RELEASE | 7.9.0.RELEASE | newest GA release; declared in `server` because the Boot 4.1.2 BOM still manages 7.8.0.RELEASE |
| HikariCP | 7.1.0 | 7.1.0 | current (Boot manages 7.0.2) |
| PostgreSQL driver | 42.7.14 | 42.7.14 | current |
| JUnit Jupiter | 6.1.3 | 6.1.3 | current |
| ArchUnit / AssertJ / awaitility / GreenMail | 1.5.1 / 3.27.7 / 4.3.0 / 2.1.15 | same | current; newest GA (AssertJ 4.0.0 exists only as a milestone) |
| Bouncy Castle (`bcmls-jdk18on`, `bcprov-jdk18on`) | 1.86 | 1.86 | current |
| OpenAPI Generator | 7.27.0 | 7.27.0 | current |
| SLF4J | 2.0.20 | 2.0.20 | newest GA release (2.1.0 exists only as alpha); declared in `server` |
| Gradle plugins (sonarqube, cyclonedx, javafx, foojay, extra-java-module-info) | 7.5.0.8588, 3.5.1, 0.1.0, 1.0.0, 1.14.2 | same | current |
| Redis image | `redis:8.10-alpine` | `redis:8.10-alpine` | current |
| Gradle build image | `gradle:9.8.2-jdk27` | `gradle:9.8.2-jdk27` | newest published image; `./gradlew` runs Gradle 9.9.1 inside it |

Process: Dependabot keeps its weekly grouped updates; each release cycle re-checks every row against live registry metadata and records the resulting pin or upgrade in the ledger. A version change is accepted only after `fullTest` passes.

Upgrade log: on 2026-11-10 every trailing pin was moved to the newest release — Gradle 9.9.0 (wrapper SHA-256 updated), Testcontainers 2.0.5 with the renamed `testcontainers-postgresql` / `testcontainers-junit-jupiter` artifacts and the `org.testcontainers.postgresql` import, Jackson 3.3.0, Flyway 13.12.1 and Lettuce 7.9.0.RELEASE declared explicitly, `redis:8.10-alpine` and `gradle:9.8.2-jdk27` in the Compose stack, Dockerfile, live-journey scripts and integration tests — the build image stays on 9.8.2 because Docker Hub has not published a `gradle` tag for 9.9.1 yet, while the wrapper inside it runs 9.9.1. On 2026-11-11 the wrapper moved to Gradle 9.9.1 with its published SHA-256 and `actions/setup-java` from v6.1.0 to v6.2.0, pinned at commit `77dd5b2`. On 2026-11-12 the stack returned to GA only — Spring Boot 4.1.2, AssertJ 3.27.7, SLF4J 2.0.20, Lettuce 7.9.0.RELEASE and JavaFX 27.0.1 replacing the pre-releases — and `fullTest` passed on the result. Verified 2026-11-12 on the GA-only stack: `xvfb-run -a ./gradlew fullTest` is green — 134 tests across the seven suites (4 skipped by design), 0 failures, and the coverage gate passes. Flyway 13.12.1, Jackson 3.3.0, Lettuce 7.9.0.RELEASE and SLF4J 2.0.20 resolve over the Boot 4.1.2 BOM, and the 27 server integration tests pass against PostgreSQL 18, Redis 8.10 and Testcontainers 2.0.5.

## Review Focus

- Forged user IDs, replayed credentials, or revoked/locked accounts must not authorize REST or WebSocket actions; cover identity and session checks in Tasks 2–4.
- Stale, rolled-back, duplicate, or out-of-order MLS state must not cause key/nonce reuse or restore removed members’ access; cover MLS persistence and epoch transitions in Task 5.
- Retried offline sends must not create duplicate messages or reorder a conversation; cover cursor sync, idempotency, reconnect, and delivery in Task 4.
- Blocking must consistently prevent discovery, requests, and new conversations in both directions; cover search and relationship transitions in Task 3.
- Date boundaries, time zones, empty months, and pagination must produce correct admin reports; cover date-range and monthly aggregation in Task 6.
- Frontend quality is a release gate, not polish: a screen that works but fails its loading/empty/error/offline, keyboard, contrast, theme or performance checks is not accepted. Apply the Frontend Quality Bar below in Tasks 6 and 7.
- MLS claims must match the implementation: until RFC 9420 conformance is proven, documentation, UI copy and release notes say MLS-style and must not claim RFC 9420.

## Frontend Quality Bar

The desktop apps are the product surface. They are held to the bar set by leading desktop messengers (Telegram Desktop, Slack, Discord, WhatsApp Desktop): calm, legible, fast, keyboard-complete, visually distinctive and free of default-toolkit artifacts. This section defines quality, not new product scope; no feature outside Project.pdf is added.

- **Architecture:** one FXML view per screen with a controller exposing observable view state and a thin view model for loading/selection/error state. Networking, cache, crypto and file I/O sit behind shared services and never run on the FX thread. A composition root per app wires dependencies; no static session, no mutable global UI state, no business logic in controllers.
- **Design system:** CSS design tokens for semantic colour roles, a 4/8 px spacing scale, type scale, radii, elevation and 120-200 ms motion, plus a documented component catalogue: buttons (primary/secondary/ghost/danger), validated fields, avatars and presence dots, badges, list cells, dialogs, toasts, skeleton loaders, empty states, tables, tabs, date-range pickers and charts. Screens compose components; no per-screen ad-hoc colours, fonts or inline styles. Shipped screens must not look like default Modena.
- **Themes and window behaviour:** light and dark themes derived from the same tokens, following the OS preference with a persisted override; both themes meet contrast targets. Persist window geometry, theme, last conversation and pane sizes per user. Support 1280x720 minimum and high-DPI/4K without clipping or overlap; the layout adapts when the details pane is hidden.
- **Visual craft:** consistent hierarchy, 8 px grid alignment, optically sized icons, and hover/pressed/focus/disabled states for every interactive control. Message grouping with day separators, avatars, clear sender attribution and readable line lengths. No clipped labels, jumping layouts or unstyled native controls.
- **Flow completeness:** every Project.pdf user and admin flow is reachable in the UI: registration, login, reset/change password, profile, discovery, requests, accept/decline, unfriend, block/unblock, friend filtering by name and presence, direct chat with history/search/delete-for-me/delete-for-everyone/clear history, report with selected evidence and consent, group create/rename/add/remove/promote/leave and encrypted messaging, admin CRUD, lock, password reset, login history, friends, groups, reports, registrations, analytics and audit. No required feature may exist only in the API.
- **UX states:** every asynchronous action exposes loading (skeleton or inline), success (unobtrusive toast), empty (explanation plus next action), offline/reconnecting and error (plain language plus retry). API problem codes map to human messages; stack traces and raw exception text never reach the user. Destructive actions confirm; long operations cancel.
- **Feedback and latency:** sending is optimistic with visible pending/failed/retry states; lists are virtualized; scroll position and selection survive refresh; no interaction blocks the FX thread for more than 100 ms; no unbounded list rendering.
- **Accessibility:** WCAG 2.2 AA for contrast, focus visibility, target size, label-in-name and error identification. Every flow is keyboard-complete with documented shortcuts; custom controls expose role, name and description; dialogs trap and restore focus; reduced motion is respected. Screen-reader audit on Windows and macOS, best effort on Linux.
- **Localization:** all user-visible strings in ResourceBundles (Vietnamese and English), locale-aware dates and numbers, no concatenated sentences, and layouts that tolerate longer translations.
- **Performance budgets:** cold start at most 2.5 s and first meaningful screen at most 1.5 s on the reference machine; interaction feedback at most 100 ms; a 10,000-message conversation scrolls at 60 fps; idle heap at most 512 MB. Measure with JavaFX startup/pulse instrumentation and track the trend in CI.
- **Quality evidence:** per-screen JavaFX tests for the happy path plus loading, empty, error and offline; golden screenshots of core screens in both themes on Linux CI with a small reviewed tolerance; a keyboard-only and screen-reader checklist each release; a static check that no user-visible string is hardcoded.

## Implementation Changes

### Task 1: Preserve the current workspace and establish the new build

- Record current Git status and review tracked, staged, and untracked changes. Create a recoverable checkpoint without including secrets; back up the existing database and keep its volume untouched.
- Replace the single-project build with Gradle subprojects: server, client-sdk, desktop-shared, desktop-user, and desktop-admin. Keep domain services in the server’s identity, social, messaging, groups, moderation, and administration packages.
- Add api/v1/openapi.yaml and api/v1/events.yaml as the contract sources. Generate the desktop SDK from those contracts; never share persistence entities with the clients.
- Add architecture rules enforcing dependency direction, no UI dependencies in the server, no persistence entities in API responses, and no cycles between server domains.
- Configure Flyway as the only schema migration mechanism and Hibernate schema validation only; disable automatic schema creation/update.

Acceptance: Clean checkout builds all modules; architecture tests reject forbidden dependencies; the existing workspace and database remain recoverable.

### Task 2: Implement identity, authentication, and profiles

- Add REST contracts for POST /api/v1/auth/register, POST /api/v1/auth/login, password reset request/confirmation, password change, and GET/PATCH /api/v1/users/me.
- Registration/profile requests include username, full name, email, address, birth date, gender, and password. Responses never include password hashes, session secrets, or admin-only fields.
- Use Spring Security with revocable opaque sessions; store only hashed refresh/session tokens. Derive identity and role from the authenticated session, never from client-supplied user IDs.
- Keep Argon2id password hashing. Replace emailing a generated password with a single-use, expiring reset token; make responses neutral for unknown email addresses and rate-limit authentication and reset requests.
- Add Flyway migrations and database constraints for normalized unique usernames/emails, required fields, and profile values.

Acceptance: Tests cover validation, duplicate accounts, locked accounts, session revocation, reset-token expiry/reuse, and authorization on every profile update.

### Task 3: Implement friends, discovery, blocking, and spam reports

- Add contracts for user search, friend requests, accept/decline, friendship list, online status, unfriend, block/unblock, and spam reports.
- Enforce relationship state transitions in server services and transactions. A block prevents search visibility and new requests in both directions; unblocking does not recreate friendship.
- Keep friend list filtering and online/offline display in the user app. Allow chat/group creation only for eligible users.
- A report contains reporter, reported user, time, metadata, and optionally user-selected message evidence. Persist the evidence as submitted content with a visible consent step; admins do not get general message access.
- Add admin moderation actions to review reports and lock/unlock accounts, with an audit record for every moderation action.

Acceptance: Integration tests cover request races, block/unblock behavior, duplicate reports, selected evidence visibility, and moderator authorization.

### Task 4: Implement direct messaging, history, search, and deletion

- Add conversation and message REST contracts with cursor-based history pagination and idempotency keys for sending. Use GET /api/v1/conversations/{id}/messages?after={cursor} for reconnect/history sync.
- Deliver real-time updates through authenticated WebSocket /ws/v1. Persist messages before fan-out; use an outbox and Redis distribution so connected clients can recover missed events from their cursor.
- Support offline message delivery, read state, history viewing, per-message deletion scopes, and per-user history clearing.
- Maintain a locally encrypted cache and full-history search index. Search results identify conversation and message IDs; selecting a result opens the conversation and scrolls to the match.
- Bound page sizes and message payloads; validate every conversation membership and sender identity on the server.

Acceptance: Integration tests cover online delivery, offline sync, retries, ordering, pagination, duplicate sends, local deletion versus sender-wide deletion, and unauthorized history access. UI tests verify search navigation and empty/no-match states.

### Task 5: Implement groups and end-to-end encryption

- Support group creation with at least one invited user, rename, add/remove members, admin assignment, and group chat. The creator is an admin; group admins may rename, add/remove members, and change admin roles; members may leave themselves.
- Use a conformant MLS implementation for RFC 9420 (Bouncy Castle is the current candidate). Persist encrypted MLS state transactionally and safely; route only ciphertext, membership metadata, and MLS protocol messages through the server. Bouncy Castle MLS API
- Generate a user-held recovery code. Store only a client-encrypted recovery package on the server; require the code to restore it. A lost device without the recovery code cannot restore prior group history.
- On membership changes, commit a new MLS epoch. Prevent removed members from receiving later application messages; newly added members receive access only from their join epoch.
- Store local group state and synchronized history in encrypted local storage; never log plaintext messages, recovery codes, or key material.
- **Known conformance gap (2026-11-07):** the current client is MLS-style, built on Bouncy Castle `bcmls 1.86` primitives, not RFC 9420. `bcmls` exposes no key-package factory and no group-state export/import, and 1.85 is excluded by a reported CVE; replaying key package, Welcome and an ordered commit log is the only viable persistence model. Until a conformant client and state model pass interop and security review, UI copy, docs and release notes must say "end-to-end encrypted group chat (MLS-style)" and must not claim RFC 9420. Decide before release: adopt a conformant implementation, build and validate state serialization against test vectors, or accept the deviation with a documented threat model.

Acceptance: MLS tests cover two- and multi-member exchanges, tampering, replay, duplicate/out-of-order commits, add/remove epoch rotation, recovery, and persistence after restart. Release is blocked until the selected MLS library version and its state-storage usage pass security review.

### Task 6: Implement all admin workflows and analytics

- Add admin-only APIs and JavaFX views for account listing/filtering/sorting, create/update/delete, lock/unlock, password reset, login history, and friend lists.
- Add group listing/filtering/sorting and member/admin inspection; spam report filtering/sorting and account locking; new registrations by date range with filtering/sorting.
- Add direct/friend-of-friend counts and user activity reports with date ranges, app opens, people chatted with, and groups chatted with. Provide monthly registration and app-open charts by selected year.
- Use server-side filtering, sorting, and pagination with allowlisted sort fields. Store timestamps as UTC instants and render them in the user’s local zone.
- Restrict admin APIs to authenticated admin sessions. Audit account deletion, lock changes, password resets, report decisions, and admin account actions.

Acceptance: PostgreSQL integration tests verify each filter, sort, date boundary, empty month, chart bucket, and admin permission. JavaFX tests exercise table filtering, sorting, navigation, loading and error states, and every admin pane meets the Frontend Quality Bar's loading/empty/error, keyboard and theme requirements.

### Task 7: Rebuild the JavaFX desktop apps to the frontend quality bar

The current desktop modules are functional shells: each app is one `Application` class with programmatic controls, no FXML, no stylesheet, no shared component library and only part of the Project.pdf flow set. Task 7 replaces them; do not restyle or extend the monoliths in place. Recommended order: auth, shell, social, messaging, groups, admin.

- **Application architecture:** one FXML view per screen with a controller owning observable view state and a thin view model for loading/selection/error state. One composition root per app wires services; no static session, no mutable global state, no business logic in controllers. Use `javafx.concurrent` or virtual threads for I/O, cancel stale requests and keep every scene-graph mutation on the FX thread.
- **Shared design system (`desktop-shared/ui`):** design tokens in CSS (semantic colour roles, 4/8 px spacing, type scale, radii, elevation, 120-200 ms motion) and the component catalogue from the Frontend Quality Bar. Remove default Modena styling from all shipped screens.
- **User app shell:** a two/three-pane messenger shell (conversation list, chat pane, optional details pane) with an account menu. Conversation rows show avatar, title, presence, last-message preview, timestamp and unread state where the data exists.
- **Auth and account screens:** login, registration with inline validation, forgot/reset password, change password, profile view/edit, logout, and session-expiry handling that returns to login without discarding recoverable drafts. Password fields support reveal, paste and correct focus order.
- **Social screens:** friends filterable by name and online status; discovery search with relation-aware actions (add, pending, friend, blocked); incoming requests accept/decline; sent requests; unfriend; block/unblock with confirmation; report a user with an explicit evidence-selection and consent step. Presence updates live without rebuilding the whole list.
- **Messaging UX:** virtualized message list with day separators, sender/avatar grouping, timestamps and delivery state (pending, sent, failed with retry); context menu for delete-for-me, delete-for-everyone (sender only) and clear history with the correct confirmation; per-conversation and global search with result navigation, match highlight and no-match state; scroll position preserved on refresh; Enter sends and Shift+Enter inserts a newline. An offline/reconnecting banner explains queued messages.
- **Group UX:** create with key-package exchange, rename, add/remove member, promote/demote admin, leave, roster with roles and presence, group-info pane, and encryption indicators that never claim more than the implementation guarantees. Removed members see a clear no-longer-a-member state; added members see history only from their join epoch.
- **Admin app shell:** sidebar/tab navigation, server-side paged and sorted tables with visible active filters, row details (login history, friends, groups, reports, audit), validated date ranges, monthly charts with empty-month handling, and explicit loading/empty/error states per pane. Destructive actions (delete, lock, password reset, report decision) confirm and show the resulting audit entry.
- **Accessibility and input:** WCAG 2.2 AA contrast, visible focus, 24x24 target size, accessible role/name/description on custom controls, label-in-name, error identification and dialog focus management. Every flow is keyboard-complete with documented shortcuts; Escape closes overlays and focus returns to the invoking control; reduced motion is respected.
- **Localization:** user-visible strings in ResourceBundles (Vietnamese and English), locale-aware formatting, no concatenated sentences, layouts that tolerate longer translations.
- **Resilience:** reconnect with backoff and cursor resync; preserve unsent drafts and queued messages across restart; never log session tokens, message plaintext, recovery codes or key material; expose one clear retry action when reconnect fails.
- **Packaging:** jpackage images/installers for Linux, Windows and macOS with app icons, version metadata and platform runtimes; signing/notarization hooks driven by repository secrets. Never claim an artifact is signed when the job did not run.

Acceptance: every Project.pdf user and admin flow is reachable and covered by a JavaFX test for its happy path plus loading, empty, error and offline states; no shipped screen uses default styling; keyboard-only journeys pass; light/dark snapshots are reviewed on Linux CI; accessibility assertions cover names, roles, focus order and contrast; startup, interaction and 10,000-message scroll meet the Frontend Quality Bar budgets; packaged apps launch on each supported OS in CI; no user-visible string is hardcoded.

**Implementation status (2026-11-09, verified):**

- [x] Admin shell is FXML/controller based and resolved through `AdminAppContext`; no static session and no API call on the FX thread.
- [x] Admin styling uses shared theme tokens plus `admin.css`; no inline FXML style, no hex colour in desktop CSS (`theme.css` uses equivalent `rgb()` token values), and no hardcoded admin UI literal.
- [x] Accounts workspace covers create, edit, delete with inline confirm/cancel, lock/unlock, admin password reset, per-user login history and per-user friend list, with loading, empty, error and success feedback.
- [x] Reports workspace shows report metadata, submitted evidence, filters and decisions; evidence is read from the report response, not a new general message-access endpoint.
- [x] EN/VI bundles cover every used admin key (199 keys referenced; 257 keys defined in each locale at this revision); dates use the shared locale-aware formatter.
- [x] Admin tests exercise the screens through FXML: login, account lock/unlock, create/edit/delete, reset password, history, friends, report evidence/decision, groups, registrations, analytics, audit and sign-out.
- [x] Destructive admin actions confirm before execution: shared confirmation boxes cover delete, lock/unlock, password reset and report uphold/dismiss, with cancel paths covered by the interaction test.
- [x] Golden screenshot coverage renders Accounts and Reports in light and dark themes at 1180x780; four images are reviewed, stored as test resources and compared with a 0.75% pixel tolerance. Regenerate with `ADMIN_UPDATE_GOLDENS=true`.
- [x] Opt-in `RealServerAdminFlowFxTest` passed 1/1 against the local PostgreSQL/Redis/server stack. It creates its own users, friendship and reported evidence, then drives edit, lock cancel/confirm, unlock, reset cancel/confirm, account create/delete, report evidence and uphold cancel/confirm through the FXML shell.
- [x] User shell and account surfaces are FXML/controller based (`login`, `shell`, `register`, `password-reset`, `account`, `group-settings`, `report`), resolved through `UserAppContext`; there is no static session and API work runs off the FX thread with loading, empty, error, retry and success feedback.
- [x] User account flows cover registration, forgot/reset password, signed-in password change, profile view/edit, discovery search with relation-aware actions, incoming accept/decline, outgoing cancel, unfriend, block/unblock with blocked list, report user/message with selected evidence and consent, per-message delete and clear history.
- [x] User registration is completed through the FXML screen and the new credentials sign in through the login screen. Duplicate registration shows a conflict, a wrong current password is identified, and a friend request to a blocked user is rejected with a specific status; the controllers are covered by loopback tests, and the opt-in live journey asserts the server's 409 duplicate, 401 wrong-password and 409 blocked-request responses.
- [x] User group settings cover rename, promote/demote, remove member and leave group. Delete, remove, leave, unfriend, block and report-triggering destructive actions use the shared `ConfirmDialog`; both cancel and confirm paths are asserted.
- [x] User EN/VI bundles cover every used key (304 keys referenced; 313 keys defined in each locale), with zero missing keys and zero parity differences.
- [x] User golden screenshot coverage renders account, register, reset, report and group-settings screens in light and dark themes at recorded sizes (960x720, 560x780, 540x640, 560x620 and 640x600); ten images are reviewed and compared with a 0.75% pixel tolerance and channel delta 16. Regenerate with `USER_UPDATE_GOLDENS=true`.
- [x] Opt-in `RealServerUserJourneyFxTest` passed 1/1 against the local PostgreSQL/Redis/server stack. It drives registration, friend request/accept, message, report with evidence, block cancel/confirm, unblock, re-friend, unfriend cancel/confirm, profile update, password change, group create/rename/promote/leave cancel/confirm, then deletes the accounts it created.
- [x] Static audit over both desktop apps: user 304 used / EN 313 / VI 313 and admin 199 used / EN 257 / VI 257, with 0 missing keys, two-way bundle parity equal, 0 inline FXML styles, 0 hex colours in desktop CSS and 0 hardcoded Java UI literal files.
- [x] Automated WCAG 2.2 AA audit of both desktop apps covers light/dark text contrast (4.5:1), non-text boundaries and focus indicators (3:1), 24x24 minimum targets with visible focus on shared controls, accessible names on user/admin inputs, lists, tables and charts, and focus restoration for shared/admin confirmation dialogs. `ThemeContrastTest`, `DesignSystemFxTest`, `UserAccountFlowFxTest`, `AdminDesignSystemFxTest` and `AdminInteractionFxTest` assert these behaviours.
- [ ] Frontend Quality Bar remains open for: manual screen-reader validation on Windows/macOS and best-effort Linux, end-to-end keyboard-only review, startup/interaction/10,000-message scroll performance budgets, and cross-platform packaging/signing.

**Status (2026-11-10) — non-functional evidence:**

- [ ] No JFR/Mission Control, async-profiler, JMH, Micrometer/Prometheus/Grafana, k6, JMeter, ZAP, Toxiproxy or Eclipse MAT usage exists in the repository. Task 9 defines the programme; none of its measurements has been taken.
- [ ] Actuator exposes `health` only and the container starts the JVM with no tuning, GC-log, heap-dump or JFR flags.
- [ ] No load-test source or performance baseline exists, so the 10,000-session / 100 msg/s / p95 < 1 s target is unproven. Phase 5 performance is "not measured".
- [ ] Three ceilings found by inspection: `OutboxPublisher` does three statements plus one Redis publish per event inside a transaction holding `FOR UPDATE SKIP LOCKED` rows on a 100 ms poll; Tomcat's default `max-connections` is 8192; Hikari's default pool is 10.
- [x] Version currency: every row of the Version Currency Policy table is on the newest release as of 2026-11-10; see the upgrade log there.

### Task 8: Harden CI/CD and release readiness

- Adapt the existing GitHub Actions, CodeQL, Dependency Review, Dependabot, Sonar, SBOM, and release workflows; retain least-privilege permissions and pinned actions rather than duplicating them.
- Run formatting/static checks, architecture tests, unit tests, PostgreSQL/Redis integration tests, WebSocket/API contract tests, JavaFX tests under Xvfb, dependency/security analysis, and package smoke tests on pull requests.
- Gate releases on all functional/security suites, SBOM generation, artifact checksums/attestations, and the configured signing jobs.
- Add repeatable load-test infrastructure for 10,000 concurrent sessions and 100 messages/second; report p95/p99 delivery latency, connection failures, database saturation, Redis fan-out, and recovery behavior.
- Document clean deployment from an empty database, TLS termination, secret configuration, backup/restore, recovery-code limitations, incident response, and release rollback.

- Gate frontend quality in CI: both `fxTest` suites, golden screenshots of core screens in light and dark, accessibility assertions (names, roles, focus, contrast), the hardcoded-string check, and startup/interaction/scroll benchmark trends.
- Fail the release when a core screen lacks loading/empty/error/offline coverage, when a theme snapshot regresses beyond the reviewed tolerance, or when a performance budget regresses by more than 10%.
- Add a nightly `performance` workflow (not run on every pull request): start `docker-compose.perf.yml`, run the k6 smoke scenario at 1,000 sessions, store the JFR recording, k6 summary, JMH JSON and MAT report as artifacts, and compare every budget against the recorded baseline.
- Block a release when no measured performance baseline exists for the tagged commit.

Acceptance: A tagged release can be built reproducibly for all supported desktop platforms and the server container; CI fails on contract breakage, security findings above the agreed gate, failed recovery/load tests, a failed frontend quality gate, or missing release artifacts.

### Task 9: Measure and tune performance and non-functional behaviour

Rationale: the 10,000-session / 100 msg/s target, the p95 delivery budget, the JavaFX startup/scroll budgets and the idle-heap limit currently have no measurement, and three configuration defaults already cap the server below the stated targets. This task adds the measurement programme, fixes the bottlenecks visible in the code, and turns every budget into a number recorded in the ledger.

Instrumentation and tuning with Java-platform tools:

- **JFR + JDK Mission Control 9** — continuous low-overhead recording in staging and on demand: `-XX:StartFlightRecording=filename=...,settings=profile,dumponexit=true`, `jcmd <pid> JFR.start|JFR.dump`, `jfr view`/`jfr print`. Answers CPU, allocation, lock, GC and socket hot spots over time and across restarts.
- **async-profiler 4.x** — CPU, allocation, lock and wall-clock flamegraphs, including wall mode for I/O-bound and JavaFX threads; emits JFR; differential flamegraphs before and after each fix.
- **JMH 1.3x** with `-prof gc,perfasm,stack` — microbenchmarks for the WebSocket frame codec, message-envelope JSON, cursor pagination mapping, MLS AES-GCM seal/open and commit handling, Argon2id login cost and JavaFX cell rendering.
- **JDK diagnostics** — `jcmd GC.class_histogram`, `jcmd Thread.print`, `jcmd VM.native_memory summary`, `jcmd Compiler.perfmap` with Linux `perf`, `-Xlog:gc*`, `-XX:+HeapDumpOnOutOfMemoryError`.
- **Eclipse MAT** — leak suspects and dominator tree from heap dumps after the one-hour soak and after a 10,000-message conversation in the client.

Load and fault injection:

- **k6** — WebSocket target: ramp to 10,000 sessions, steady 100 msg/s, spike, reconnect storm, 30-minute hold and one-hour soak, with thresholds that fail the run on p95/p99, error rate or any connection failure after ramp.
- **JMeter** — REST matrix driven by CSV users with the HTML dashboard: history paging, login, admin queries, per-endpoint throughput and latency.
- **Toxiproxy** (Testcontainers module) — latency, partition, duplicate and reset faults; asserts reconnect with cursor resync and no lost or duplicated message.
- **PostgreSQL** — `pg_stat_statements`, `auto_explain`, `EXPLAIN (ANALYZE, BUFFERS)` and `pgbench`; no sequential scan on a hot path, plus index and bloat review.
- **Redis** — `redis-benchmark` on the fan-out channel, `SLOWLOG GET`, `LATENCY DOCTOR`, `INFO commandstats` and `INFO memory`.

Observability:

- **Micrometer + Prometheus + Grafana** — add `micrometer-registry-prometheus` and expose `health,info,metrics,prometheus` (heap and thread dumps stay off the public surface). Meters: outbox lag and pending rows, WebSocket session count, publish latency, Hikari pool usage, Redis listener lag and GC pause time. Grafana dashboards ship in `docker-compose.perf.yml`.

Fixes required before measuring:

1. `OutboxPublisher` issues one target query, one Redis publish and one UPDATE per event, all inside a single transaction holding `FOR UPDATE SKIP LOCKED` rows, polled every 100 ms. Batch the target lookup with `conversation_id = ANY(?)`, batch `UPDATE ... WHERE event_id = ANY(?)`, pipeline the Redis publishes, publish outside the lock window and wake the worker with `LISTEN/NOTIFY`.
2. Tomcat's default `max-connections` is 8192, below the 10,000 target. Set `server.tomcat.max-connections`, `accept-count`, `threads.max` and `connection-timeout`, then prove them with k6.
3. Hikari's default pool is 10. Size it from the CPU budget, set `connection-timeout`, `leak-detection-threshold` in staging and `reWriteBatchedInserts=true`.
4. Enable `spring.threads.virtual.enabled=true` for the blocking JDBC and mail paths.
5. Actuator exposes `health` only; add the Prometheus registry and the endpoint list above.
6. The container declares no JVM flags. Add `-XX:MaxRAMPercentage`, `-XX:+ExitOnOutOfMemoryError`, heap-dump flags, `-Xlog:gc*` and an opt-in JFR recording. Choose G1 or ZGC from measurements, not by default.
7. Redis uses one shared channel, so every instance processes every event. Measure the listener load before changing the topology; use sharded pub/sub only when more than one instance measurably degrades delivery.
8. Add `docker-compose.perf.yml`: PostgreSQL with `pg_stat_statements` and `auto_explain`, Prometheus, Grafana, k6, Toxiproxy and a server started with JFR enabled.

Acceptance — every item is measured and recorded in the ledger: 10,000 WebSocket sessions with 100 msg/s for 30 minutes and a one-hour soak; zero connection failures after ramp; p95 online delivery below one second and p99 below two seconds; error rate below 0.1%; stable RSS and heap with GC pause p99 below 100 ms; history paging p95 below 200 ms at 200 rps; login p95 recorded against the Argon2id cost; ZAP with zero high findings; JavaFX cold start at most 2.5 s, first meaningful screen at most 1.5 s, 10,000-message scroll at 60 fps, no FX-thread block above 100 ms, idle heap at most 512 MB. A nightly job stores the JFR, k6, JMH and MAT artifacts and fails when a budget regresses by more than 10%.

**Progress (2026-11-10):** the three code-level ceilings and the runtime configuration are fixed — the outbox now batches its membership lookup, Redis publishes and marker update (one shared-connection publish loop and one marker update instead of three statements per event), Tomcat is configured for 20,000 connections, Hikari for 16 connections with batched inserts, virtual threads are enabled, Actuator serves Prometheus with `chat.outbox.*` and `chat.ws.*` meters, and the container runs with heap, OOM, heap-dump and GC-log flags.

**Measured baseline (2026-11-10, single host, `docker-compose.perf.yml`):** 10,000 concurrent WebSocket sessions ramped over 60 s with a constant 100 messages/second — **p95 delivery 106 ms, p99 126 ms, max 244 ms**; server-side send p95 113 ms and p99 142 ms; `POST /messages` p95 4.7 ms, p99 13.8 ms with 0 failed requests; socket connect p95 1.4 ms; outbox batch max 33 ms; server RSS 1.6 GiB of a 3 GiB limit.

JFR analysis of the first run (`jfr summary`, `hot-methods`, `gc-pauses`, `thread-cpu-load`, `latencies-by-type`) ruled out CPU, GC and session revalidation, and showed the real tail: presence heartbeats issued three Redis commands per session on a single scheduler thread (30,000 serial commands per 30 s), which saturated Redis and blocked the outbox poller. Batching the heartbeat into one pipeline and giving the scheduler two threads moved p99 from 605 ms (and 1.88 s server-side) to the numbers above. An earlier attempt at the same target also failed: an instantaneous 10,000-socket burst against a 32-connection pool produced `HikariPool ... request timed out after 3004ms` and stalled at 2,186 sockets, which is why the run now ramps and the pool is 64.

Two client/server mismatches from that run are now fixed. Lettuce 7 opts in to `CLIENT MAINT_NOTIFICATIONS` on every RESP3 handshake, but that subcommand is Redis Enterprise/Cloud only, so OSS Redis 8.10 rejected it and the client surfaced an exception per connection; `lettuce-core` is on 7.9.0.RELEASE and a `LettuceClientOptionsBuilderCustomizer` disables the feature. The outbox also published through `executePipelined`, which borrows a dedicated Lettuce connection — a new TCP connection and handshake per 100 ms batch (552 connections for 549 batches); it now publishes on the shared connection, and `total_connections_received` grows by 34 over a full run. `redis-cli MONITOR` around a restart shows `HELLO 3` + `CLIENT SETINFO` only, and a repeat load run holds at p95 106 ms / p99 148 ms. The first run after a restart also reported a much worse tail (p99 590 ms) because the JVM was cold, so `perf/k6/chat-load.js` gained a third phase: sockets ramp for `RAMP` (60 s), `WARMUP` (60 s) of traffic at `RATE` runs and is reported separately as `chat_delivery_warmup_ms`, and only then does the measured plateau of `HOLD` seconds start, with socket hold extended to cover warm-up plus plateau. Senders stamp the phase into the message content (`t=<epoch>;p=w|m;`), so the receiving socket attributes each sample without a shared clock. With warm-up, a fresh JVM now reports p95 105 ms / p99 116 ms, and the warm-up window itself reports p95 106 ms / p99 116 ms — the cost was in socket ramp and first-message compilation, and it no longer reaches the reported numbers.

**Soak result (2026-11-10, 30-minute plateau at 10,000 sessions and 100 msg/s, 15 s telemetry sampling):** delivery **p95 112 ms, p99 132 ms, max 538 ms**; 180,000 messages sent and 360,000 deliveries with **0 of 188,857 checks failed**; `POST /messages` p95 8.45 ms, p99 13.6 ms; socket connect p95 3.6 ms; outbox lag 0 s with 10–11 rows per batch; Hikari at most 2 active connections. GC: 745 pauses, 2.83 s of total pause in 32 minutes (0.15% of wall time), mean 3.8 ms, worst sampled pause 39 ms — the 100 ms GC budget holds. Heap: median 835 MiB, peak 1,164 MiB. RSS: 1.29–1.40 GiB for the first 28 minutes, then a single step to 1.84 GiB at minute 28 that stays flat to the end, still inside the 3 GiB limit; it coincides with connection churn (11,428 sockets opened for 10,000 VUs, i.e. 1,428 reconnects) and is not yet attributed to a cause — a heap dump at the same point (MAT) is the next step. Raw samples are in `build/perf/soak-samples.tsv` and the k6 summary in `build/perf/k6-soak.log`.

**One-hour soak result (2026-11-11, same profile, 15 s telemetry, heap dumps at minute 15 and 59):** delivery **p95 110 ms, p99 116 ms**, average 62 ms; 359,966 messages sent and 719,932 deliveries with **0 of 368,823 checks failed** (368,823 iterations); `POST /messages` p95 7.3 ms, p99 10.2 ms, max 896 ms; socket connect p95 3.4 ms; 11,428 sockets opened for 10,000 VUs. GC: 1,655 pauses, 4.82 s of total pause in 62 minutes (0.13% of wall time), mean 2.9 ms, longest pause 104.7 ms — inside the 100 ms budget only marginally, and that pause is the full GC that `jcmd GC.heap_dump` forces. RSS averaged 1,416 MiB over the first 30 minutes and 1,456 MiB over the last 30 (+2.8%), peak 1,558 MiB of the 3 GiB limit. Heap used averaged 829 MiB and then 833 MiB; committed heap peaked at 1,100 MiB. The 1.09 s delivery maximum and the 896 ms `POST` maximum both fall on the two dumps, which are stop-the-world, and are harness artifacts rather than server behaviour.

**Who holds the memory (Eclipse MAT 1.17.0, heap dumps at minute 15 and 59):** the live heap after the dump's full GC is 790.7 MiB at minute 15 and 788.4 MiB at minute 59 — a decrease of 2.2 MiB over 44 minutes, and a class-histogram diff between the two dumps shows no class growing by more than 0.4 MiB (the largest deltas are Netty/Lettuce pooled buffers and recycler handles, i.e. churn). The heap is the connections themselves: `WsFrameServer` holds 337.5 MB (40.8%, 10,000 instances with 20,000 `char[]` of 8.3 KB each), `NioEndpoint$NioSocketWrapper` 173.2 MB (20.9%) and `WsRemoteEndpointImplServer` 171.8 MB (20.7%, 40,000 `byte[]` of 4.1 KB each), plus one precomputed `Argon2Function` at 34.2 MB. That is roughly 79 KB of heap per live session, and the top dominators are identical in both dumps, so the one-hour run shows no leak and no retained history accumulating. MAT's leak-suspect report ranks those three connection classes as its three suspects, which is expected for 10,000 idle-but-open sockets, not a defect. The RSS step seen at minute 28 in the earlier 30-minute run did not reproduce on a fresh JVM under identical load: RSS and both dumps stay flat, so it was heap commit/allocation burst behaviour, not retained objects.

**Ramp to the ceiling (2026-11-11, `perf/k6/chat-ramp.js` driven by `perf/ramp-run.sh`):** the target was never the question, so four staged runs held a plateau long enough to report per-stage p95/p99 from the receiving socket at 100 msg/s. 10,000 → 14,000 → 18,000 sessions held at **p95 112 ms / 112 ms / 115 ms and p99 559 ms / 135 ms / 176 ms**. The next stage found the first real wall: a 22,000 target stalled at **19,999 sessions**, exactly `server.tomcat.max-connections` (`CHAT_MAX_CONNECTIONS=20000`), and delivery collapsed to **p95 367 ms, p99 1.27 s, max 106 s** while accepted sends fell from 100/s to 10.2/s and `POST /messages` returned the 60 s client timeout. Heap (1.67 GiB), GC pause (0.26% of wall time), Hikari (5 of 64 connections active) and outbox lag all stayed flat, so that stage measured the connection cap, not saturation. Raising only the cap and the container limit, the same build held **22,000 (p95 108 ms, p99 817 ms)**, **26,000 (p95 111 ms, p99 340 ms, max 757 ms, heap 2.08 GiB of 6 GiB)** and **30,000 sessions at p95 110 ms, p99 189 ms, max 593 ms** with 0 of 23,024 requests failed, 30,122 open file descriptors and GC pause at 0.16% of wall time. Delivery latency is flat from 10,000 to 30,000 sessions, which is the evidence that the server is still far from its knee at three times the original target.

Two generator-side walls had to be removed first, and both looked like server faults until the telemetry was read. The k6 container's default ephemeral port range (32768-60999) tops out near 28,000 connections to a single server address: 30,000 and 34,000 targets died with 4,431 `connect: cannot assign requested address` failures, a retry storm that pushed the generator to 684% CPU and reported 141,862 rejected sockets while the server sat at 8% CPU and 2.8 GiB of 6 GiB. Widening the range (`net.ipv4.ip_local_port_range=1024 65535`, `tcp_tw_reuse`) removed every one of those errors and the generator dropped to 13% CPU. The fixture reset had the same character: `DELETE FROM user_account WHERE username LIKE 'load\_%'` cascaded through 659,000 messages and ran for over 429 s because `message` has no index on its sender, so `perf/seed.sql` now truncates the disposable perf schema instead. That was a harness fix, but it was also a production gap: `AdminService.deleteUser` walks the same unindexed cascade, so deleting one account cost time proportional to the whole message table. Migration `V7__account_delete_indexes.sql` indexes every foreign key that references `user_account` (18 columns, including `message.sender_id` and the already-partial `conversation_member.user_id`), and `AccountDeletionCostIntegrationTest` deletes an account out of a 200,000-message table, fails unless the cascade seeks messages through that index rather than filtering a wider index or scanning the table, and records the duration. Measured on the perf database with 200,000 messages in 200 conversations, one account delete takes **24 ms without the index and 23.6 ms with it**, because a 1,000-row delete is dominated by row and trigger work at that size; the index removes the O(table) part that only shows up when the table is large, which is why the fixture reset spent 429 s repeating that scan 10,000 times.

An index audit of the other hot paths found the same class of gap on the outbox, which V7 did not cover because its foreign keys point at `conversation`, `message` and `mls_message` rather than at `user_account`. Deleting a single message spent **20.96 ms inside `message_outbox_message_id_fkey`**, a sequential scan of 400,000 events to find the one that referenced it, and `conversation_direct_low_id` could not use the partial `conversation_direct_pair_idx` either. Migration `V8__outbox_foreign_key_indexes.sql` adds those four indexes and `MessageDeleteCostIntegrationTest` writes one event per message out of 100,000, fails unless both lookups plan against the new indexes, and records the delete duration. Every other hot path measured index-backed at the same data volume: outbox poll 0.78 ms, outbox mark 1.93 ms, message paging 1.12 ms / 0.56 ms, conversation and group lists 0.05 ms, friends 0.16 ms, incoming requests 0.38 ms. The one remaining scan is user search: `POSITION` on the username cannot use a btree, so a term that matches nobody reads all 20,000 rows in 6.6 ms, and that grows linearly until the search moves to a prefix or trigram index.

**Ceiling statement (2026-11-11):** the server's measured ceiling is **at least 30,000 concurrent sessions at 100 msg/s with p95 delivery at 110 ms**, three times the release target, and the wall that stopped the first ramp was configuration rather than capacity. What stops a higher ramp today is the single host that runs both k6 and the server: each held socket costs the generator one ephemeral port and roughly 230 KB of RAM (6.7 GiB of k6 at 30,000 VUs). Heap measured 84 KB per session, so a 6 GiB heap should carry about 60,000 sessions, but proving it needs a second generator host, and that remains outstanding.

The soak was restarted once because the seeded `user_session` rows had expired (12-hour TTL): every handshake returned 401, k6 reconnected in a loop and exhausted the client's ephemeral ports (`connect: cannot assign requested address`), which made that run invalid. Re-extending the fixture sessions fixed it. The perf stack mounts a host JDK read-only at `/opt/jdk` (`PERF_JDK_HOME`) because the runtime image is a JRE without `jcmd`, which is what makes the on-demand dumps and class histograms possible. Still outstanding: JMeter REST matrix, JMH microbenchmarks, ZAP, Toxiproxy fault injection, multi-host testing and the client-side heap check after a 10,000-message conversation.

**Status (2026-11-11):** the WebSocket target has a reproducible harness with warm-up, a stable baseline, a 30-minute soak, a completed one-hour soak and a staged ramp that reached 30,000 sessions at p95 110 ms (see above): delivery p95 110 ms / p99 116 ms at 10,000 sessions and 100 msg/s, no failed checks, live heap flat and MAT showing that the 790 MiB live set is the 10,000 sockets at ~79 KB each. The ramp also proved that the first ceiling was `CHAT_MAX_CONNECTIONS`, not the machine, and that the remaining limit on this box is the load generator itself. JFR capture, Prometheus metrics, k6 thresholds and MAT heap analysis are in place; async-profiler, JMH, JMeter, ZAP, Toxiproxy and multi-host testing are still missing, so the delivery and server-heap budgets are proven but the startup/scroll, idle-heap and client-side memory budgets remain partly proven. The JavaFX startup/scroll, idle-heap, REST paging, one-hour soak and fault-injection budgets are therefore unproven — Phase 5 performance work is partly measured, not done. The five trailing pins were moved to the newest release on 2026-11-10, and the three code-level ceilings above (outbox fan-out, Tomcat `max-connections`, Hikari pool size) must be fixed before the load test can be meaningful.

## Test Plan

- Unit: domain transitions, validation, authorization rules, analytics aggregation, cursor/idempotency behavior, and client state management.
- Integration: Testcontainers PostgreSQL and Redis; migrations from empty schema; REST auth; WebSocket delivery; offline/reconnect behavior; admin access; audit records.
- Crypto/security: MLS-style protocol tests; RFC 9420 interop test vectors only if a conformant implementation is adopted; tamper/replay/state rollback, membership epoch changes, recovery-code restore, storage/log redaction, and dependency review.
- Frontend: per-screen FXML/controller loading, happy path plus loading/empty/error/offline, keyboard-only journeys, focus management, light/dark theme snapshots, accessibility name/role/contrast assertions, localization fallback, startup and large-history scroll budgets, and packaged-app startup.
- End to end: register → discover/request/accept → direct chat → offline delivery → group creation → encrypted messages → member removal → report with selected evidence → admin review/lock → password reset.
- Performance: 10,000 concurrent sessions and 100 messages/second; p95 online delivery under one second; capture resource saturation and reconnect behavior. Measurement uses the Task 9 programme — JFR/Mission Control and async-profiler for hot spots, JMH for codec/JSON/crypto microbenchmarks, k6 for the WebSocket target, JMeter for the REST matrix, `pg_stat_statements`/`pgbench` for PostgreSQL, `redis-benchmark`/`SLOWLOG`/`LATENCY` for fan-out, Micrometer/Prometheus/Grafana for the budgets, Eclipse MAT for leak suspects and Toxiproxy for network faults. ZAP covers runtime security; the JavaFX budgets are measured from pulse logging, frame-time recording and client JFR.

## Assumptions and Defaults

- Clean cutover means the new application starts with a fresh schema; existing user/message data is not migrated. Preserve the old database as a backup/archive.
- Initial release remains JavaFX desktop for users and administrators, with a server deployment; mobile/web clients and multi-device group identity are out of scope.
- Java 27 is the baseline because it is the newest release; LTS status does not drive the choice, and every component follows the Version Currency Policy until a newer release is adopted.
- The plan began as a documentation-only revision. Implementation has since started; verified status belongs in `.superpowers/sdd/chat-system-rewrite/progress.md`, not in assumptions above.
- Skipped: voice/video and other features outside Project.pdf. Risk: end-to-end encryption, recovery, and 10,000-user performance require independent security and load validation before release.
