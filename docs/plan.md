# Production Chat System Rewrite Implementation Plan

> For agentic workers: Use superpowers:executing-plans and implement one task at a time. Do not discard or overwrite the existing modified workspace.
>
> Status lives in `.superpowers/sdd/chat-system-rewrite/progress.md`; neither this plan nor that ledger is evidence of completion. Verify the current source, migrations, tests and runtime evidence before claiming a task is done.

Goal: Replace the current chat system with a production-grade, user-oriented, accessible and visually polished cross-platform JavaFX user and admin application backed by a secure, horizontally scalable server that covers every feature in Project.pdf.

Architecture: A Spring Boot modular monolith will serve versioned REST operations and secure WebSocket events. Separate JavaFX user and admin apps will use generated API contracts; PostgreSQL stores application data, while Redis supports distributed presence and event fan-out. Group messages use MLS-style end-to-end encryption on Bouncy Castle primitives; RFC 9420 conformance is an open release blocker, so docs and UI must not claim RFC 9420. Message search runs against a locally encrypted history index.

Tech Stack: Java 27, Gradle 9.8.2, JavaFX 27, Spring Boot 4.1.2, PostgreSQL 18, Redis, Flyway, Spring Security, Bouncy Castle MLS, JUnit Jupiter, Testcontainers, JavaFX UI tests, JaCoCo, GitHub Actions.

Spec: Project.pdf plus the approved design decisions in this conversation.

## Global Constraints

- Java 27 is the selected baseline. It is compatible with Spring Boot 4.1.2, but it is a short-lived non-LTS release; Java 25 is the current LTS release. Treat the pinned versions as the approved baseline: do not upgrade dependencies ad hoc. Before release, run a version-currency review covering Java 27 versus Java 25 LTS, Spring Boot, JavaFX, Bouncy Castle and PostgreSQL patch levels, and record the decision.
- Keep the product within the declaration: no voice/video, mobile app, channels, or unrelated Telegram features.
- Treat the existing schema as a clean cutover: create a new versioned schema and make no legacy-data compatibility promise. Back up the old database and leave it intact.
- Protect the current modified and untracked workspace before implementation; inspect changes and preserve them in a checkpoint before replacing files.
- Support 10,000 concurrent client connections and 100 messages/second in the production-like load test, with online message delivery below one second at p95.
- Group encryption targets RFC 9420 MLS; the current implementation is MLS-style and must be described that way. New members can read messages from their join epoch onward; removed members cannot decrypt later epochs. RFC 9420
- End-to-end encryption applies to group messages. Direct messages use TLS and server-side storage protection.
- Search all synchronized history locally using an encrypted index. The server must not receive plaintext search terms.
- Admins may review only report metadata and message evidence explicitly selected and submitted by the reporting user.
- Users can delete messages for themselves; only the sender can delete a message for everyone. Clearing history affects only the requesting user.

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

### Task 8: Harden CI/CD and release readiness

- Adapt the existing GitHub Actions, CodeQL, Dependency Review, Dependabot, Sonar, SBOM, and release workflows; retain least-privilege permissions and pinned actions rather than duplicating them.
- Run formatting/static checks, architecture tests, unit tests, PostgreSQL/Redis integration tests, WebSocket/API contract tests, JavaFX tests under Xvfb, dependency/security analysis, and package smoke tests on pull requests.
- Gate releases on all functional/security suites, SBOM generation, artifact checksums/attestations, and the configured signing jobs.
- Add repeatable load-test infrastructure for 10,000 concurrent sessions and 100 messages/second; report p95/p99 delivery latency, connection failures, database saturation, Redis fan-out, and recovery behavior.
- Document clean deployment from an empty database, TLS termination, secret configuration, backup/restore, recovery-code limitations, incident response, and release rollback.

- Gate frontend quality in CI: both `fxTest` suites, golden screenshots of core screens in light and dark, accessibility assertions (names, roles, focus, contrast), the hardcoded-string check, and startup/interaction/scroll benchmark trends.
- Fail the release when a core screen lacks loading/empty/error/offline coverage, when a theme snapshot regresses beyond the reviewed tolerance, or when a performance budget regresses by more than 10%.

Acceptance: A tagged release can be built reproducibly for all supported desktop platforms and the server container; CI fails on contract breakage, security findings above the agreed gate, failed recovery/load tests, a failed frontend quality gate, or missing release artifacts.

## Test Plan

- Unit: domain transitions, validation, authorization rules, analytics aggregation, cursor/idempotency behavior, and client state management.
- Integration: Testcontainers PostgreSQL and Redis; migrations from empty schema; REST auth; WebSocket delivery; offline/reconnect behavior; admin access; audit records.
- Crypto/security: MLS-style protocol tests; RFC 9420 interop test vectors only if a conformant implementation is adopted; tamper/replay/state rollback, membership epoch changes, recovery-code restore, storage/log redaction, and dependency review.
- Frontend: per-screen FXML/controller loading, happy path plus loading/empty/error/offline, keyboard-only journeys, focus management, light/dark theme snapshots, accessibility name/role/contrast assertions, localization fallback, startup and large-history scroll budgets, and packaged-app startup.
- End to end: register → discover/request/accept → direct chat → offline delivery → group creation → encrypted messages → member removal → report with selected evidence → admin review/lock → password reset.
- Performance: 10,000 concurrent sessions and 100 messages/second; p95 online delivery under one second; capture resource saturation and reconnect behavior.

## Assumptions and Defaults

- Clean cutover means the new application starts with a fresh schema; existing user/message data is not migrated. Preserve the old database as a backup/archive.
- Initial release remains JavaFX desktop for users and administrators, with a server deployment; mobile/web clients and multi-device group identity are out of scope.
- The single Java 27 baseline is retained despite its shorter support window; revisit before release if long-term maintenance outweighs that constraint.
- The plan began as a documentation-only revision. Implementation has since started; verified status belongs in `.superpowers/sdd/chat-system-rewrite/progress.md`, not in assumptions above.
- Skipped: voice/video and other features outside Project.pdf. Risk: end-to-end encryption, recovery, and 10,000-user performance require independent security and load validation before release.
