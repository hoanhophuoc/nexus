package com.haichutieu.chatsystem.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.haichutieu.chatsystem.server.identity.NewUserRegistration;
import com.haichutieu.chatsystem.server.identity.PasswordResetRepository;
import com.haichutieu.chatsystem.server.identity.ProfileUpdateRequest;
import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import com.haichutieu.chatsystem.server.identity.SessionRepository;
import com.haichutieu.chatsystem.server.identity.UserAccountRepository;
import com.haichutieu.chatsystem.server.social.PresenceStore;
import com.haichutieu.chatsystem.server.social.SocialConflictException;
import com.haichutieu.chatsystem.server.social.ReportEvidenceSubmission;
import com.haichutieu.chatsystem.server.social.ReportSubmission;
import com.haichutieu.chatsystem.server.social.SocialRepository;
import com.haichutieu.chatsystem.server.administration.AdminAnalyticsService;
import com.haichutieu.chatsystem.server.administration.AdminConflictException;
import com.haichutieu.chatsystem.server.administration.AdminNotFoundException;
import com.haichutieu.chatsystem.server.administration.AdminReportDecisionRequest;
import com.haichutieu.chatsystem.server.administration.AdminService;
import com.haichutieu.chatsystem.server.administration.AdminUserLockRequest;
import com.haichutieu.chatsystem.server.administration.ActivityAnalytics;
import com.haichutieu.chatsystem.server.administration.FriendAnalytics;
import com.haichutieu.chatsystem.server.identity.RegistrationRequest;
import com.haichutieu.chatsystem.server.groups.GroupService;
import com.haichutieu.chatsystem.server.groups.GroupForbiddenException;
import com.haichutieu.chatsystem.server.groups.KeyPackageService;
import com.haichutieu.chatsystem.server.identity.UserNotFoundException;
import com.haichutieu.chatsystem.server.groups.GroupConflictException;
import com.haichutieu.chatsystem.server.groups.MlsConflictException;
import com.haichutieu.chatsystem.server.groups.MlsMessageView;
import com.haichutieu.chatsystem.server.groups.MlsNotFoundException;
import com.haichutieu.chatsystem.server.groups.MlsService;
import com.haichutieu.chatsystem.server.messaging.MessagingRepository;
import com.haichutieu.chatsystem.server.messaging.ConversationForbiddenException;
import com.haichutieu.chatsystem.server.messaging.IdempotencyConflictException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class IdentityMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordResetRepository passwordResets;

    @Autowired
    private SocialRepository social;

    @Autowired
    private MessagingRepository messaging;

    @Autowired
    private AdminService admin;

    @Autowired
    private AdminAnalyticsService analytics;

    @Autowired
    private GroupService groups;

    @Autowired
    private MlsService mls;

    @Autowired
    private KeyPackageService keyPackages;

    @Autowired
    private PresenceStore presence;

    @Test
    void flywayCreatesIdentityTablesOnlyInTheVersionedSchema() {
        Set<String> tables = jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'chat_v1'
                """, String.class).stream().collect(Collectors.toSet());

        assertThat(tables).contains(
                "flyway_schema_history",
                "user_account",
                "user_session",
                "password_reset_token",
                "login_history",
                "admin_audit_event",
                "friendship",
                "friend_request",
                "user_block",
                "spam_report",
                "spam_report_evidence",
                "moderation_action",
                "conversation",
                "conversation_member",
                "message",
                "message_hidden_for_user",
                "message_outbox",
                "mls_key_package");
        assertThat(tables).doesNotContain("customer");
    }

    @Test
    void sessionsAreRevocableAndLockedAccountsLoseAccess() {
        UUID userId = createUser("active");
        String hash = "a".repeat(64);
        sessions.create(hash, userId, Instant.now().plusSeconds(3600));

        assertThat(sessions.authenticate(hash)).contains(new SessionIdentity(userId, "user"));
        sessions.revoke(hash);
        assertThat(sessions.authenticate(hash)).isEmpty();

        UUID lockedUserId = createUser("locked");
        String lockedHash = "b".repeat(64);
        sessions.create(lockedHash, lockedUserId, Instant.now().plusSeconds(3600));
        jdbcTemplate.update("UPDATE chat_v1.user_account SET locked = TRUE WHERE id = ?", lockedUserId);
        assertThat(sessions.authenticate(lockedHash)).isEmpty();
    }

    @Test
    void passwordChangeKeepsCurrentSessionAndRevokesTheOthersAtomically() {
        UUID userId = createUser("password-change");
        String current = "c".repeat(64);
        String other = "d".repeat(64);
        sessions.create(current, userId, Instant.now().plusSeconds(3600));
        sessions.create(other, userId, Instant.now().plusSeconds(3600));

        assertThat(sessions.changePassword(userId, "unused", "new-hash", current)).isTrue();

        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM chat_v1.user_account WHERE id = ?",
                String.class, userId)).isEqualTo("new-hash");
        assertThat(sessions.authenticate(current)).isPresent();
        assertThat(sessions.authenticate(other)).isEmpty();
    }

    @Test
    void profileUpdatePersistsFieldsAndNormalizedEmail() {
        UUID userId = UUID.randomUUID();
        users.create(new NewUserRegistration(userId, "alice", "alice", "Alice", "Alice@Example.com",
                "alice@example.com", "Address", LocalDate.of(1990, 1, 1), "female", "password-hash"));

        var updated = users.updateProfile(userId, new ProfileUpdateRequest(
                "Alice Example", "new@example.com", null, null, null)).orElseThrow();

        assertThat(updated.fullName()).isEqualTo("Alice Example");
        assertThat(updated.email()).isEqualTo("new@example.com");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_normalized FROM chat_v1.user_account WHERE id = ?", String.class, userId))
                .isEqualTo("new@example.com");
    }

    @Test
    void resetTokenCanBeConsumedOnceBeforeExpiryAndRevokesSessions() {
        UUID userId = createUser("reset-token");
        String tokenHash = "f".repeat(64);
        String sessionHash = "e".repeat(64);
        sessions.create(sessionHash, userId, Instant.now().plusSeconds(3600));
        passwordResets.create(tokenHash, userId, Instant.now().plusSeconds(900));

        assertThat(passwordResets.consume(tokenHash, "reset-password-hash")).isTrue();
        assertThat(passwordResets.consume(tokenHash, "second-password-hash")).isFalse();
        assertThat(sessions.authenticate(sessionHash)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM chat_v1.user_account WHERE id = ?",
                String.class, userId)).isEqualTo("reset-password-hash");

        String expiredHash = "g".repeat(64);
        passwordResets.create(expiredHash, userId, Instant.now().minusSeconds(1));
        assertThat(passwordResets.consume(expiredHash, "expired-password-hash")).isFalse();
    }

    @Test
    void blockingHidesBothUsersRejectsRequestsAndUnblockingDoesNotRestoreFriendship() {
        UUID alice = createUser("social-alice");
        UUID bob = createUser("social-bob");
        social.sendRequest(alice, bob);
        var request = social.incomingRequests(bob, null, 20).items().getFirst();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> social.sendRequest(bob, alice))
                .isInstanceOf(SocialConflictException.class);
        social.decideRequest(bob, request.id(), true);
        assertThat(social.canStartDirectConversation(alice, bob)).isTrue();

        social.block(alice, bob);

        assertThat(social.friends(alice, null, 20).items()).isEmpty();
        assertThat(social.incomingRequests(alice, null, 20).items()).isEmpty();
        assertThat(social.incomingRequests(bob, null, 20).items()).isEmpty();
        assertThat(social.search(alice, "social-bob", null, 20).items()).isEmpty();
        assertThat(social.search(bob, "social-alice", null, 20).items()).isEmpty();
        assertThat(social.canStartDirectConversation(alice, bob)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> social.sendRequest(alice, bob))
                .isInstanceOf(SocialConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> social.sendRequest(bob, alice))
                .isInstanceOf(SocialConflictException.class);

        social.unblock(alice, bob);

        assertThat(social.search(alice, "social-bob", null, 20).items()).hasSize(1);
        assertThat(social.canStartDirectConversation(alice, bob)).isFalse();
    }

    @Test
    void reportStoresOnlyExplicitlySubmittedEvidenceAndRejectsDuplicatePendingReport() {
        UUID reporter = createUser("reporter");
        UUID reported = createUser("reported");
        UUID messageId = UUID.randomUUID();
        UUID reportId = social.submitReport(reporter, new ReportSubmission(reported, "spam", true,
                java.util.List.of(new ReportEvidenceSubmission(messageId, "user selected evidence"))));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT submitted_content FROM chat_v1.spam_report_evidence WHERE report_id = ? AND message_id = ?",
                String.class, reportId, messageId)).isEqualTo("user selected evidence");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> social.submitReport(reporter,
                new ReportSubmission(reported, "duplicate", false, null)))
                .isInstanceOf(SocialConflictException.class);
    }

    @Test
    void directMessagingRequiresFriendshipAndRetriesAreIdempotent() {
        UUID alice = createUser("message-alice");
        UUID bob = createUser("message-bob");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messaging.createDirectConversation(alice, bob))
                .isInstanceOf(ConversationForbiddenException.class);
        social.sendRequest(alice, bob);
        var request = social.incomingRequests(bob, null, 20).items().getFirst();
        social.decideRequest(bob, request.id(), true);

        UUID conversationId = messaging.createDirectConversation(alice, bob).id();
        assertThat(messaging.createDirectConversation(bob, alice).id()).isEqualTo(conversationId);
        var first = messaging.sendMessage(alice, conversationId, "request-key-00000001", "hello");
        assertThat(messaging.sendMessage(alice, conversationId, "request-key-00000001", "hello").id())
                .isEqualTo(first.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                messaging.sendMessage(alice, conversationId, "request-key-00000001", "different"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(messaging.messages(bob, conversationId, null, 20).items()).containsExactly(first);

        social.block(alice, bob);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messaging.createDirectConversation(bob, alice))
                .isInstanceOf(ConversationForbiddenException.class);
    }

    @Test
    void clearingAndHidingHistoryAffectOnlyTheRequestingUser() {
        UUID alice = createUser("history-alice");
        UUID bob = createUser("history-bob");
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        UUID conversation = messaging.createDirectConversation(alice, bob).id();
        var message = messaging.sendMessage(alice, conversation, "request-key-00000002", "keep for bob");
        messaging.deleteMessage(alice, conversation, message.id(),
                com.haichutieu.chatsystem.server.messaging.MessageDeletionScope.me);
        assertThat(messaging.messages(alice, conversation, null, 20).items()).isEmpty();
        assertThat(messaging.messages(bob, conversation, null, 20).items()).containsExactly(message);
        messaging.clearHistory(bob, conversation);
        assertThat(messaging.messages(bob, conversation, null, 20).items()).isEmpty();
        assertThat(messaging.messages(alice, conversation, null, 20).items()).isEmpty();
    }

    @Test
    void messageCursorPreservesConversationOrderAndSenderCanDeleteForEveryone() {
        UUID alice = createUser("cursor-alice");
        UUID bob = createUser("cursor-bob");
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        UUID conversation = messaging.createDirectConversation(alice, bob).id();
        var first = messaging.sendMessage(alice, conversation, "request-key-cursor-0001", "first");
        var second = messaging.sendMessage(bob, conversation, "request-key-cursor-0002", "second");
        var third = messaging.sendMessage(alice, conversation, "request-key-cursor-0003", "third");

        var page = messaging.messages(alice, conversation, null, 2);
        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.nextCursor()).isEqualTo(second.id().toString());
        assertThat(messaging.messages(alice, conversation, UUID.fromString(page.nextCursor()), 2).items())
                .containsExactly(third);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messaging.deleteMessage(bob, conversation,
                first.id(), com.haichutieu.chatsystem.server.messaging.MessageDeletionScope.everyone))
                .isInstanceOf(com.haichutieu.chatsystem.server.messaging.MessageDeletionForbiddenException.class);
        messaging.deleteMessage(alice, conversation, first.id(),
                com.haichutieu.chatsystem.server.messaging.MessageDeletionScope.everyone);
        assertThat(messaging.messages(bob, conversation, null, 20).items().getFirst().deletedForEveryoneAt())
                .isNotNull();
    }

    @Test
    void adminFilteringSortingLockingAndAuditUseAuthenticatedAdminIdentity() {
        UUID administrator = createUser("admin-" + UUID.randomUUID().toString().substring(0, 8));
        jdbcTemplate.update("UPDATE chat_v1.user_account SET role = 'admin' WHERE id = ?", administrator);
        String prefix = "adm-" + UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser(prefix + "-alice");
        UUID bob = createUser(prefix + "-bob");
        SessionIdentity actor = new SessionIdentity(administrator, "admin");

        var page = admin.users(actor, prefix, "active", "name", "asc", null, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotBlank();
        var secondPage = admin.users(actor, prefix, "active", "name", "asc", page.nextCursor(), 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(secondPage.items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
        assertThat(java.util.List.of(page.items().getFirst().id(), secondPage.items().getFirst().id()))
                .containsExactly(alice, bob);

        String sessionHash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        sessions.create(sessionHash, bob, Instant.now().plusSeconds(3600));
        admin.setUserLock(actor, bob, new AdminUserLockRequest(true, "confirmed abuse report"));
        assertThat(sessions.authenticate(sessionHash)).isEmpty();
        assertThat(admin.users(actor, prefix, "locked", "name", "asc", null, 10).items())
                .extracting(item -> item.id()).containsExactly(bob);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT details ->> 'reason' FROM chat_v1.admin_audit_event
                WHERE actor_id = ? AND target_id = ? AND action = 'user.lock'
                """, String.class, administrator, bob)).isEqualTo("confirmed abuse report");
    }

    @Test
    void groupMembershipEnforcesRolesFriendshipAndPreservesAdminAccess() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser("group-a-" + suffix);
        UUID bob = createUser("group-b-" + suffix);
        UUID charlie = createUser("group-c-" + suffix);
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);

        var group = groups.create(alice, "  project team  ", java.util.List.of(bob));
        assertThat(group.name()).isEqualTo("project team");
        assertThat(group.members()).extracting(member -> member.role()).containsExactlyInAnyOrder("admin", "member");
        assertThat(groups.list(bob, null, 20).items()).extracting(item -> item.id()).containsExactly(group.id());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> groups.rename(bob, group.id(), "stolen"))
                .isInstanceOf(GroupForbiddenException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> groups.addMember(alice, group.id(), charlie))
                .isInstanceOf(GroupForbiddenException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> groups.removeMember(alice, group.id(), alice))
                .isInstanceOf(GroupConflictException.class);

        social.sendRequest(alice, charlie);
        social.decideRequest(charlie, social.incomingRequests(charlie, null, 20).items().getFirst().id(), true);
        social.block(bob, charlie);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> groups.addMember(alice, group.id(), charlie))
                .isInstanceOf(GroupForbiddenException.class);
        social.unblock(bob, charlie);
        groups.addMember(alice, group.id(), charlie);
        groups.setRole(alice, group.id(), charlie, com.haichutieu.chatsystem.server.groups.GroupRoleRequest.Role.admin);
        groups.removeMember(alice, group.id(), alice);
        assertThat(groups.list(alice, null, 20).items()).isEmpty();
        assertThat(groups.members(charlie, group.id(), null, 20).items())
                .extracting(member -> member.user().id()).containsExactlyInAnyOrder(bob, charlie);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> groups.rename(alice, group.id(), "left"))
                .isInstanceOf(com.haichutieu.chatsystem.server.identity.UserNotFoundException.class);
    }

    @Test
    void adminCreatesUpdatesDeletesAccountsAndRecordsAudit() {
        SessionIdentity actor = createAdminIdentity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var created = admin.createUser(actor, new RegistrationRequest("crud-" + suffix, "Crud User",
                "crud-" + suffix + "@example.com", "address", LocalDate.of(1995, 5, 5), "female",
                "long-enough-password"));

        assertThat(created.status()).isEqualTo("active");
        assertThat(created.fullName()).isEqualTo("Crud User");
        var updated = admin.updateUser(actor, created.id(),
                new ProfileUpdateRequest("Crud Renamed", null, null, null, null));
        assertThat(updated.fullName()).isEqualTo("Crud Renamed");
        assertThat(admin.user(actor, created.id()).username()).isEqualTo("crud-" + suffix);
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM chat_v1.admin_audit_event WHERE target_id = ?", String.class, created.id()))
                .contains("user.create", "user.update");
        assertThatThrownBy(() -> admin.user(actor, UUID.randomUUID()))
                .isInstanceOf(AdminNotFoundException.class);

        admin.deleteUser(actor, created.id());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_v1.user_account WHERE id = ?", Integer.class, created.id())).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM chat_v1.admin_audit_event WHERE target_id = ?", String.class, created.id()))
                .contains("user.delete");
        assertThatThrownBy(() -> admin.deleteUser(actor, actor.userId()))
                .isInstanceOf(com.haichutieu.chatsystem.server.administration.AdminForbiddenException.class);
    }

    @Test
    void adminReviewExposesOnlySubmittedEvidenceAndAnUpheldDecisionLocksTheReportedAccount() {
        SessionIdentity actor = createAdminIdentity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID reporter = createUser("mod-rep-" + suffix);
        String reportedName = "mod-tgt-" + suffix;
        UUID reported = createUser(reportedName);
        UUID messageId = UUID.randomUUID();
        UUID reportId = social.submitReport(reporter, new ReportSubmission(reported, "harassment", true,
                java.util.List.of(new ReportEvidenceSubmission(messageId, "the exact excerpt"))));

        var page = admin.reports(actor, reportedName, null, null, "createdAt", "desc", null, 50);
        var report = page.items().stream().filter(item -> item.id().equals(reportId)).findFirst().orElseThrow();
        assertThat(report.status()).isEqualTo("pending");
        assertThat(report.reporter().id()).isEqualTo(reporter);
        assertThat(report.selectedEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.messageId()).isEqualTo(messageId);
            assertThat(evidence.submittedContent()).isEqualTo("the exact excerpt");
        });

        String sessionHash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        sessions.create(sessionHash, reported, Instant.now().plusSeconds(3600));
        admin.decideReport(actor, reportId, new AdminReportDecisionRequest("uphold", true, "confirmed abuse"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM chat_v1.spam_report WHERE id = ?",
                String.class, reportId)).isEqualTo("upheld");
        assertThat(jdbcTemplate.queryForObject("SELECT locked FROM chat_v1.user_account WHERE id = ?",
                Boolean.class, reported)).isTrue();
        assertThat(sessions.authenticate(sessionHash)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT action FROM chat_v1.moderation_action WHERE report_id = ?
                """, String.class, reportId)).isEqualTo("report.uphold");
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM chat_v1.admin_audit_event WHERE target_id = ?", String.class, reported))
                .contains("user.lock");
        assertThatThrownBy(() -> admin.decideReport(actor, reportId,
                new AdminReportDecisionRequest("dismiss", false, null)))
                .isInstanceOf(AdminConflictException.class);
    }

    @Test
    void adminRegistrationRangesAndMonthlyChartsUseUtcHalfOpenIntervals() {
        SessionIdentity actor = createAdminIdentity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID user = createUser("reg-" + suffix);
        Instant now = Instant.now();

        var page = admin.registrations(actor, now.minusSeconds(60), now.plusSeconds(60), "reg-" + suffix,
                "createdAt", "desc", null, 50);

        assertThat(page.items()).extracting(item -> item.id()).containsExactly(user);
        assertThatThrownBy(() -> admin.registrations(actor, now, now, null, "name", "asc", null, 50))
                .isInstanceOf(IllegalArgumentException.class);

        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        int month = LocalDate.now(ZoneOffset.UTC).getMonthValue();
        var registrations = analytics.monthlyRegistrations(actor, year);
        assertThat(registrations.months()).hasSize(12);
        assertThat(registrations.months().get(month - 1).count()).isGreaterThanOrEqualTo(1);
        assertThat(analytics.monthlyRegistrations(actor, 2001).months())
                .allSatisfy(bucket -> assertThat(bucket.count()).isZero());

        jdbcTemplate.update("INSERT INTO chat_v1.login_history (id, user_id, occurred_at) VALUES (?, ?, ?)",
                UUID.randomUUID(), user, Timestamp.from(now));
        assertThat(analytics.monthlyAppOpens(actor, year).months().get(month - 1).count())
                .isGreaterThanOrEqualTo(1);
        assertThat(analytics.monthlyAppOpens(actor, 2001).months())
                .allSatisfy(bucket -> assertThat(bucket.count()).isZero());
        assertThatThrownBy(() -> analytics.activity(actor, now, now.minusSeconds(1), null, null, null,
                "name", "asc", null, 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminFriendAnalyticsCountsDirectAndSecondDegreeFriends() {
        SessionIdentity actor = createAdminIdentity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID dave = createUser("dave-" + suffix);
        UUID erin = createUser("erin-" + suffix);
        UUID frank = createUser("frank-" + suffix);
        social.sendRequest(dave, erin);
        social.decideRequest(erin, social.incomingRequests(erin, null, 20).items().getFirst().id(), true);

        var page = analytics.friends(actor, suffix, null, null, "name", "asc", null, 50);
        assertThat(page.items()).extracting(FriendAnalytics::directFriends).containsExactly(1, 1, 0);
        assertThat(page.items()).extracting(FriendAnalytics::friendsOfFriends).containsExactly(0, 0, 0);

        var noFriends = analytics.friends(actor, suffix, 0, "eq", "name", "asc", null, 50);
        assertThat(noFriends.items()).extracting(item -> item.user().id()).containsExactly(frank);

        social.sendRequest(erin, frank);
        social.decideRequest(frank, social.incomingRequests(frank, null, 20).items().getFirst().id(), true);

        var secondDegree = analytics.friends(actor, suffix, null, null, "name", "asc", null, 50);
        assertThat(secondDegree.items()).extracting(FriendAnalytics::friendsOfFriends).containsExactly(1, 0, 1);
        assertThatThrownBy(() -> analytics.friends(actor, suffix, null, "gt", "name", "asc", null, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminActivityAnalyticsCountsAppOpensChatPartnersAndGroupsInRange() {
        SessionIdentity actor = createAdminIdentity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser("act-a-" + suffix);
        UUID bob = createUser("act-b-" + suffix);
        UUID idle = createUser("act-idle-" + suffix);
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        UUID direct = messaging.createDirectConversation(alice, bob).id();
        messaging.sendMessage(alice, direct, "activity-direct-0001", "hello");
        var group = groups.create(alice, "activity group " + suffix, java.util.List.of(bob));
        mls.sendMessage(alice, group.id(), 0, "activity-group-0001", new byte[]{7, 8, 9});
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO chat_v1.login_history (id, user_id, occurred_at) VALUES (?, ?, ?)",
                UUID.randomUUID(), alice, Timestamp.from(now));
        jdbcTemplate.update("INSERT INTO chat_v1.login_history (id, user_id, occurred_at) VALUES (?, ?, ?)",
                UUID.randomUUID(), bob, Timestamp.from(now));

        var page = analytics.activity(actor, now.minusSeconds(60), now.plusSeconds(60), suffix,
                null, null, "name", "asc", null, 50);

        assertThat(page.items()).extracting(ActivityAnalytics::appOpens).containsExactly(1, 1, 0);
        assertThat(page.items()).extracting(ActivityAnalytics::peopleChattedWith).containsExactly(1, 1, 0);
        assertThat(page.items()).extracting(ActivityAnalytics::groupsChattedWith).containsExactly(1, 1, 0);
        var idleOnly = analytics.activity(actor, now.minusSeconds(60), now.plusSeconds(60), suffix,
                0, "eq", "name", "asc", null, 50);
        assertThat(idleOnly.items()).extracting(item -> item.user().id()).containsExactly(idle);
    }

    @Test
    void adminLoginHistoryGroupInspectionAndAuditListingUseAdminIdentity() {
        SessionIdentity actor = createAdminIdentity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID user = createUser("hist-" + suffix);
        UUID friend = createUser("hist-f-" + suffix);
        Instant occurred = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("INSERT INTO chat_v1.login_history (id, user_id, occurred_at) VALUES (?, ?, ?)",
                UUID.randomUUID(), user, Timestamp.from(occurred));

        assertThat(admin.loginHistory(actor, user, null, 50).items()).anySatisfy(entry -> {
            assertThat(entry.userId()).isEqualTo(user);
            assertThat(entry.occurredAt()).isEqualTo(occurred);
        });
        assertThat(admin.allLoginHistory(actor, null, 100).items()).isNotEmpty();

        social.sendRequest(user, friend);
        social.decideRequest(friend, social.incomingRequests(friend, null, 20).items().getFirst().id(), true);
        var group = groups.create(user, "hist group " + suffix, java.util.List.of(friend));

        assertThat(admin.groups(actor, "hist group " + suffix, "name", "asc", null, 10).items())
                .extracting(item -> item.id()).containsExactly(group.id());
        assertThat(admin.groupMembers(actor, group.id(), null, 10).items())
                .extracting(member -> member.user().id()).containsExactlyInAnyOrder(user, friend);
        assertThat(admin.userFriends(actor, user, null, 10).items())
                .extracting(friendUser -> friendUser.id()).containsExactly(friend);

        admin.setUserLock(actor, friend, new AdminUserLockRequest(true, "audit listing check"));

        assertThat(admin.auditEvents(actor, null, 50).items()).isNotEmpty();
        assertThatThrownBy(() -> admin.groupMembers(actor, UUID.randomUUID(), null, 10))
                .isInstanceOf(AdminNotFoundException.class);
    }

    @Test
    void mlsCommitsAdvanceTheEpochAndRejectStaleOrReplayedCommits() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser("mls-a-" + suffix);
        UUID bob = createUser("mls-b-" + suffix);
        UUID outsider = createUser("mls-o-" + suffix);
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        var group = groups.create(alice, "mls group " + suffix, java.util.List.of(bob));

        byte[] commit = "first MLS commit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mls.submitCommit(alice, group.id(), 0, commit);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_epoch FROM chat_v1.conversation WHERE id = ?", Long.class, group.id()))
                .isEqualTo(1L);
        assertThat(mls.messages(bob, group.id(), null, 20).items())
                .extracting(MlsMessageView::kind).containsExactly("commit");
        assertThatThrownBy(() -> mls.submitCommit(alice, group.id(), 0,
                "late".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(MlsConflictException.class);
        assertThatThrownBy(() -> mls.submitCommit(bob, group.id(), 1, commit))
                .isInstanceOf(MlsConflictException.class);
        assertThatThrownBy(() -> mls.submitCommit(outsider, group.id(), 1, new byte[]{1}))
                .isInstanceOf(com.haichutieu.chatsystem.server.identity.UserNotFoundException.class);
    }

    @Test
    void mlsApplicationMessagesAreIdempotentAndHiddenBeforeTheJoinEpoch() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser("mls-msg-a-" + suffix);
        UUID bob = createUser("mls-msg-b-" + suffix);
        UUID charlie = createUser("mls-msg-c-" + suffix);
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        social.sendRequest(alice, charlie);
        social.decideRequest(charlie, social.incomingRequests(charlie, null, 20).items().getFirst().id(), true);
        var group = groups.create(alice, "mls messages " + suffix, java.util.List.of(bob));

        byte[] ciphertext = {1, 2, 3};
        var sent = mls.sendMessage(alice, group.id(), 0, "mls-message-key-0001", ciphertext);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM chat_v1.message_outbox WHERE mls_message_id = ?
                """, Long.class, sent.id())).isEqualTo(1L);
        assertThat(mls.sendMessage(alice, group.id(), 0, "mls-message-key-0001", ciphertext).id())
                .isEqualTo(sent.id());
        assertThatThrownBy(() -> mls.sendMessage(alice, group.id(), 0, "mls-message-key-0001",
                new byte[]{9, 9}))
                .isInstanceOf(com.haichutieu.chatsystem.server.messaging.IdempotencyConflictException.class);
        assertThatThrownBy(() -> mls.sendMessage(alice, group.id(), 3, "mls-message-key-0002", new byte[]{1}))
                .isInstanceOf(MlsConflictException.class);

        groups.addMember(alice, group.id(), charlie);

        assertThat(mls.messages(charlie, group.id(), null, 20).items()).isEmpty();
        mls.submitCommit(alice, group.id(), 0,
                "membership commit".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(mls.messages(charlie, group.id(), null, 20).items())
                .extracting(MlsMessageView::kind).containsExactly("commit");

        var page = mls.messages(bob, group.id(), null, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(mls.messages(bob, group.id(), UUID.fromString(page.nextCursor()), 1).items())
                .extracting(MlsMessageView::kind).containsExactly("commit");

        groups.removeMember(alice, group.id(), charlie);
        assertThatThrownBy(() -> mls.messages(charlie, group.id(), null, 20))
                .isInstanceOf(com.haichutieu.chatsystem.server.identity.UserNotFoundException.class);
    }

    @Test
    void redisPresenceTracksMultipleConnectionsPerUser() {
        UUID user = createUser("presence-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(presence.isOnline(user)).isFalse();
        presence.online(user, "connection-a");
        presence.online(user, "connection-b");
        assertThat(presence.isOnline(user)).isTrue();
        assertThat(presence.onlineAmong(Set.of(user))).contains(user);

        presence.offline(user, "connection-a");
        assertThat(presence.isOnline(user)).isTrue();
        presence.offline(user, "connection-b");
        assertThat(presence.isOnline(user)).isFalse();
    }

    @Test
    void keyPackagesAreReplaceableAndMissingPackagesAreNotFound() {
        UUID user = createUser("key-package-" + UUID.randomUUID().toString().substring(0, 8));

        assertThatThrownBy(() -> keyPackages.find(user)).isInstanceOf(UserNotFoundException.class);
        keyPackages.publish(user, new byte[]{1, 2, 3});
        assertThat(keyPackages.find(user)).isEqualTo(new byte[]{1, 2, 3});
        keyPackages.publish(user, new byte[]{4, 5});
        assertThat(keyPackages.find(user)).isEqualTo(new byte[]{4, 5});
    }

    @Test
    void mlsRecoveryPackagesArePerMemberReplaceableAndOpaque() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser("mls-rec-a-" + suffix);
        UUID bob = createUser("mls-rec-b-" + suffix);
        UUID outsider = createUser("mls-rec-o-" + suffix);
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        var group = groups.create(alice, "mls recovery " + suffix, java.util.List.of(bob));

        mls.storeRecoveryPackage(alice, group.id(), new byte[]{1, 2, 3});
        assertThat(mls.recoveryPackage(alice, group.id())).isEqualTo(new byte[]{1, 2, 3});
        mls.storeRecoveryPackage(alice, group.id(), new byte[]{4, 5});
        assertThat(mls.recoveryPackage(alice, group.id())).isEqualTo(new byte[]{4, 5});
        assertThatThrownBy(() -> mls.recoveryPackage(bob, group.id()))
                .isInstanceOf(MlsNotFoundException.class);
        assertThatThrownBy(() -> mls.storeRecoveryPackage(outsider, group.id(), new byte[]{1}))
                .isInstanceOf(com.haichutieu.chatsystem.server.identity.UserNotFoundException.class);
    }

    @Test
    void plaintextGroupMessagesAreRejectedInFavorOfMlsCiphertext() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID alice = createUser("plain-a-" + suffix);
        UUID bob = createUser("plain-b-" + suffix);
        social.sendRequest(alice, bob);
        social.decideRequest(bob, social.incomingRequests(bob, null, 20).items().getFirst().id(), true);
        var group = groups.create(alice, "plaintext guard " + suffix, java.util.List.of(bob));

        assertThatThrownBy(() -> messaging.sendMessage(alice, group.id(), "plaintext-group-0001", "hello"))
                .isInstanceOf(ConversationForbiddenException.class);
        assertThatThrownBy(() -> messaging.messages(alice, group.id(), null, 20))
                .isInstanceOf(ConversationForbiddenException.class);
    }

    private SessionIdentity createAdminIdentity() {
        UUID id = createUser("admin-" + UUID.randomUUID().toString().substring(0, 8));
        jdbcTemplate.update("UPDATE chat_v1.user_account SET role = 'admin' WHERE id = ?", id);
        return new SessionIdentity(id, "admin");
    }

    private UUID createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO chat_v1.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, DATE '1990-01-01', 'undisclosed', 'unused')
                """, id, username, username, username, username + "@example.com", username + "@example.com", "test");
        return id;
    }
}
