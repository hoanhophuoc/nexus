# Chat System Rewrite — Continuation Handoff

**Prepared:** 2026-11-05 (Asia/Ho_Chi_Minh)  
**Workspace:** /home/phuochoan/Projects/Chat-System  
**Purpose:** Context for the next coding agent. The current implementation is partial and is not production-ready.

## 1. User objective and working rules

The user authorized continuation of the production rewrite from the prior implementation plan. The plan is the source of scope. The task is not complete.

Read first:

1. Repository AGENTS.md for user/project instructions. Reply to the user in Vietnamese; keep code, identifiers, and technical docs in English. Preserve the existing modified workspace. Use project Gradle/JDK pins and Ponytail full.
2. Project.pdf for the original assignment.
3. This file and actual current source. This handoff can become stale.

Work autonomously on reversible local edits and tests; avoid unnecessary approval questions. Do not deploy, publish, push, delete user data, or infer other irreversible operations. Multi-agent tools are disabled in the current session; the user will manually give this file to another agent.

For a fresh agent session, load superpowers:using-superpowers, then continue the relevant superpowers workflow for implementation planning, TDD, debugging, and verification. Use Ponytail full: smallest complete solution, preserve validation/security, add tests for nontrivial behavior. Do not commit the dirty workspace without an explicit reason.

## 2. Product and architecture scope

The goal is a JavaFX desktop user application, a separate JavaFX admin app, and a secure horizontally scalable server covering Project.pdf. Keep scope limited to the assignment: no voice/video, mobile app, public channels, or unrelated Telegram features.

Selected stack and architecture:

- Java 27, Gradle 9.8.2, JavaFX 27.
- Spring Boot modular monolith; versioned REST and authenticated WebSocket.
- Modules currently include server, client-sdk, desktop-shared, desktop-user, desktop-admin. The original root Java/JavaFX project still exists.
- PostgreSQL with Flyway migrations; Hibernate schema auto-create/update must stay disabled. Server currently uses JDBC + Flyway.
- Redis for rate limits and event fan-out; distributed presence is not finished.
- Group messages must use RFC 9420 MLS end-to-end encryption. Direct messages use TLS and server-side storage protection; current code stores plaintext content in PostgreSQL.
- Search over synchronized message history must run locally over an encrypted index. Current cache is an encrypted snapshot with linear scans, not the planned scalable encrypted index.
- Admins may inspect report metadata and only evidence explicitly selected/submitted by the reporter.
- Users can hide a message for themselves; only its sender can delete for everyone; clearing history affects only the requesting user.
- Clean schema cutover: no legacy-data migration promise. Preserve the existing database/volume. No destructive database operations.
- Target load gate: 10,000 concurrent sessions, 100 messages/sec, online delivery p95 under 1 second. No current load test proves this.

Original Project.pdf requirements:

- **Admin:** user list/filter/sort, create/update/delete, lock/unlock, password update, login history, friend list; chronological login list; group list/filter/sort and member/admin inspection; spam report filtering and account locking; registration list by date range and monthly registrations chart; direct and friend-of-friend counts; activity reports (app opens, direct people, groups) by date range and monthly app-open chart.
- **User:** registration/profile/password reset/change/login; friends filtered by name and online status; discovery and friend requests; unfriend/block/unblock; incoming request accept/decline; online friends; chat/group creation; spam reports; real-time and offline messaging, history, clear/delete messages, per-conversation and global local search with navigation; group rename/add/admin/remove/chat/encrypted messaging.

Security review focus from the accepted plan:

- Never trust client-supplied user IDs for the actor. REST/WebSocket identity comes from revocable authenticated sessions; locked/revoked accounts lose access.
- Idempotent offline retries must not duplicate or reorder messages; reconnect syncs from a cursor.
- Blocking prevents discovery, new requests, and new conversations in both directions.
- MLS epochs must prevent rollback/key reuse; removed members cannot decrypt future epochs; new members cannot read pre-join history.
- Admin evidence is explicit and audited; no general message access.
- Admin date ranges use UTC instants and correct half-open intervals/month buckets/pagination; display in local time.

## 3. Workspace safety

The worktree was already very dirty before this continuation, with modifications to original tracked files plus many new rewrite files. Do not run git checkout/reset/clean or blanket formatting that could overwrite this work. Do not stage or commit everything by default. Inspect git status before further changes.

A pre-rewrite recovery archive was reported at:

    /tmp/chat-system-workspace-before-rewrite.tar.gz

This archive does not replace preserving the current worktree.

settings.gradle includes client-sdk, desktop-shared, desktop-user, desktop-admin, server, but the root legacy JavaFX application remains. This is not yet the complete Task 1 replacement.

Root build pins Java 27 and JavaFX 27.0.1; wrapper is Gradle 9.8.2; server Spring Boot plugin is 4.1.2. Preserve those choices unless the user changes the approved baseline. Java 27 was intentionally selected despite not being LTS.

## 4. Current implementation status

Treat this section as navigation, not proof. Inspect code and rerun tests before relying on it.

### Build, contracts, schema — partial Task 1

- Gradle modules above exist, but the old root application is still built.
- OpenAPI contract: api/v1/openapi.yaml. Event contract: api/v1/events.yaml. SDK generation is wired through Gradle.
- Server migrations currently: V1__identity_schema.sql, V2__social_schema.sql, V3__direct_messaging_schema.sql under server/src/main/resources/db/migration.
- No MLS migration/state persistence exists.
- Architecture tests exist at the root and in server, but they do not prove the full dependency/architecture requirements.

### Identity/auth — partial Task 2

- Registration/profile/login/logout/password change/reset REST and services exist.
- Argon2id via Password4j; opaque session cookies; only token hashes in DB; identity/role resolved server-side; CSRF exists.
- Password reset uses expiring token and rate limiting, not emailed generated passwords.
- AuthenticationService and PasswordResetService had ambiguous Spring constructors fixed by annotating the intended constructors with @Autowired.
- JDBC writes for session/reset expiry now use Timestamp.from(Instant), after PostgreSQL rejected raw Instant parameters.
- Existing unit/integration tests cover some identity/session/reset behavior.

### Social, discovery, reports — partial Task 3

- Search, friend requests, accept/decline, friend list, unfriend, block/unblock, and spam reports with selected evidence exist.
- Transactional repository logic locks users and checks blocks; tests cover basic block and evidence rules.
- Presence is not implemented: mapped SocialUser records often say offline. Redis distributed presence is missing.
- Complete admin moderation/report filtering/decisions remain incomplete.

### Direct messages and local cache — partial Task 4

- Server has direct conversation creation (requires friendship and no block), cursor history, idempotent sends, deletion scopes, per-user clearing, Redis outbox fan-out, authenticated WebSocket checks.
- desktop-shared has session-scoped API/cookies, encrypted local message cache, password-derived key, offline queue/idempotency, history sync, reconnect/backoff and event application.
- Cache key is PBKDF2-HMAC-SHA256 from the login password with account-scoped constant salt. It does not use OS credential storage; password reset/change may make the old local cache inaccessible.
- Cache is a single encrypted snapshot and linear-searches history. A shortcut comment identifies the need for encrypted indexed storage at large history sizes.
- Direct message contents are plaintext in PostgreSQL. TLS protects transit; DB/storage encryption at rest is not implemented.
- Realtime and retry behavior are not load-tested; current tests cover only some delivery/history/idempotency cases.

### Group management — first partial server slice

Current continuation added these classes:

- server/src/main/java/com/haichutieu/chatsystem/server/groups/GroupRepository.java
- JdbcGroupRepository.java
- GroupService.java and GroupController.java
- Request/page/role/exception records in that package.
- Error mapping in server/src/main/java/com/haichutieu/chatsystem/server/identity/IdentityExceptionHandler.java.
- Unit test GroupServiceTest.java.
- Integration method groupMembershipEnforcesRolesFriendshipAndPreservesAdminAccess in IdentityMigrationIntegrationTest.java.
- OpenAPI now documents cursor/limit parameters for group member pagination.

Current group JDBC behavior:

- Create with trimmed nonblank 1–120 char name, at least one distinct invitee, maximum 100. Creator admin, invitees members.
- Invitee must be a friend of the acting creator/admin; reject blocked or locked users. Creation/invitation lock active user rows before relationship checks so block operations serialize against these checks.
- List groups and active members with UUID cursor and bounded page size.
- Rename/add/remove/role changes require active group admin. Member can leave self.
- Prevent last admin from leaving/demotion if other active members remain.
- Duplicate active member is conflict. Rejoining resets history visibility to the current group sequence.
- Nonmember cannot inspect active roster or manage it.

Latest group verification:

    GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew :server:test --tests '*GroupServiceTest' :server:compileIntegrationTestJava
    BUILD SUCCESSFUL in 6s

    GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew --no-daemon :server:integrationTest --tests '*groupMembershipEnforcesRolesFriendshipAndPreservesAdminAccess'
    BUILD SUCCESSFUL in 1m 2s

The focused integration test passed, but background OutboxPublisher logs database connection errors after Testcontainers shuts down, and Redis listener logs reconnect attempts during teardown. Cleanup can wait around 30 seconds. Investigate lifecycle/test profile settings rather than suppressing production errors.

Group gaps and risks:

- **MLS is not implemented.** OpenAPI contains /groups/{groupId}/mls-commits, /mls-messages, /recovery-package, but the server has no handlers, persistence, MLS state, or tests. joinedEpoch is currently always 0. Add/remove does not commit an MLS epoch. Never describe groups as encrypted or production-ready.
- CreateGroupRequest includes name and invitedUserIds only. OpenAPI also has optional keyPackages, which server ignores. Resolve the invitation/key-package API before implementing MLS.
- Group message send/read is absent. JdbcMessagingRepository.lockConversation only permits type=direct.
- Group integration test covers role enforcement, friendship eligibility, last-admin protection, leave, and roster access. Missing: concurrency races, block/locked invite cases, rejoin/history boundaries, pagination boundaries, HTTP auth/CSRF/status, audit.
- Review UUID cursor semantics and lock ordering before scaling. Pair comparison in JdbcGroupRepository uses string UUID order to match JdbcSocialRepository.

### Desktop apps — partial Task 7

- Separate JavaFX user/admin launchers compile.
- User shell supports login, direct conversation list/history, local cache/search, offline queue/sync, WebSocket reconnect/logout. It lacks complete profile/friends/requests/groups/reports and OS credential-store use.
- Admin shell supports login, account list/filter/status/lock only; no full Project.pdf workflows or analytics.
- No dedicated JavaFX interaction tests for these two new modules. Earlier check found xvfb-run missing; recheck before concluding UI runtime tests are impossible.
- Cross-platform jpackage/signing/release claims in docs/CI-CD.md must be checked against actual Gradle task definitions/workflows. Some documentation may be stale or aspirational.

### Admin and analytics — partial Task 6

- Admin user list/filter/sort/pagination and lock/unlock endpoints exist. AdminService checks authenticated role, prevents self-lock and locking last unlocked admin, revokes sessions when locking, records audit event.
- Admin UI uses only these functions.
- Missing: full user CRUD, login history, friend list; group admin listing/inspection; spam moderation; registration-range lists/charts; friend-of-friend counts; activity counts/charts; full audit of all required admin actions.
- Date-range/monthly aggregation and local timezone rendering are not implemented/verified.

### CI/CD, release, load — partial Task 8

- Workflows exist for CI, CodeQL, dependency review, Dependabot and release; Docker files/docs exist.
- Verify they invoke all current module test tasks and that referenced packaging tasks exist. docs/CI-CD.md currently describes test counts/coverage/package behaviors from an earlier project state; do not treat its claims as evidence without checking.
- No repeatable 10k-session/100-msg-per-sec load test exists.
- No confirmed code signing/notarization; never claim release artifacts are signed unless jobs/secrets are actually configured.
- Deployment/backup/restore/incident/rollback docs need a consistency review against current runtime.

## 5. Latest workspace changes

New/untracked files in the latest group slice:

    server/src/main/java/com/haichutieu/chatsystem/server/groups/
    server/src/test/java/com/haichutieu/chatsystem/server/groups/GroupServiceTest.java

Modified in the latest slice:

    server/src/main/java/com/haichutieu/chatsystem/server/identity/IdentityExceptionHandler.java
    server/src/integrationTest/java/com/haichutieu/chatsystem/server/IdentityMigrationIntegrationTest.java
    api/v1/openapi.yaml

No migration was added for group management because V3 already has generic conversation and conversation_member tables with group type/role. There is no MLS epoch or group state in the schema.

## 6. Next steps

1. Inspect the actual current worktree and the current group files. Do not overwrite existing user changes.
2. Run focused and full checks:

       GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew :server:test --tests '*GroupServiceTest'
       GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew --no-daemon :server:integrationTest --tests '*groupMembershipEnforcesRolesFriendshipAndPreservesAdminAccess'
       GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew :server:test :server:integrationTest :desktop-shared:test :desktop-user:build :desktop-admin:build build

   The focused tests passed most recently. The full command has not been rerun after current group changes. Investigate the test teardown errors at root cause.
3. Add group integration/HTTP tests for blocked and locked invitees, concurrency races, duplicate/rejoin behavior, history boundaries, pagination, nonmember roster access, authenticated actor derivation, 401/403/404/409, validation and CSRF.
4. Implement MLS before calling group messaging complete. Security review exact Bouncy Castle artifact/version/API and RFC 9420 state/storage model. References:
   - RFC 9420: https://www.rfc-editor.org/rfc/rfc9420.html
   - Bouncy Castle MLS Java API: https://downloads.bouncycastle.org/java/docs/bcmls-jdk18on-javadoc/org/bouncycastle/mls/package-summary.html
   The BC docs consulted previously describe an MLS client state machine (key-package generation, join, send/receive, Welcome handling); verify exact artifact/version/API. Define atomic expected-epoch commit semantics, replay/duplicate/out-of-order rejection, restart persistence, add/remove epoch rotation, recovery package encryption, and application-message idempotency. Do not treat opaque bytes as cryptographically valid unless they are actually validated. Do not store plaintext key material/server-held private state.
5. Wire MLS/group messages to desktop-shared and desktop-user. Keep local MLS state encrypted; handle offline/reconnect cursors; ensure added users only get post-join epochs and removed users cannot receive later ciphertext. Add crypto tests; never log plaintext, recovery codes or keys.
6. Complete all admin workflows and analytics, user UI flows, test coverage, CI/release readiness, load testing, deployment docs from the accepted plan. Do not narrow completion to group management.
7. Before marking the overall goal complete, audit every Project.pdf item and every task/acceptance criterion in the rewrite plan against implementation, tests, CI and runtime evidence.

## 7. Previous build evidence

Before the current group changes, the following command passed:

    GRADLE_USER_HOME=/tmp/chat-system-gradle-home ./gradlew --no-daemon :server:test :server:integrationTest :desktop-shared:test :desktop-user:build :desktop-admin:build build
    BUILD SUCCESSFUL

The server integration test class then had 11 tests. An admin test initially collided with another test's constant session hash; it was changed to a unique 64-character hash and subsequently passed. After those earlier fixes, the current group unit and focused PostgreSQL integration checks also passed as listed above. Do not use older green results as proof for changes made later.

Server integration uses Testcontainers PostgreSQL 18 and Redis. Local Gradle cache used in recent commands: /tmp/chat-system-gradle-home. Use the wrapper; do not upgrade dependencies just to modernize.

## 8. Overall handoff status

There is meaningful partial implementation through identity, social, direct messaging, desktop shells, admin user locking, and a first group-management slice. The promised production rewrite is **not complete**. Largest blockers are RFC 9420 MLS/E2EE and group messaging, unfinished Project.pdf workflows, absent load validation at target scale, incomplete JavaFX UI tests/package verification, and unverified CI/release claims.

