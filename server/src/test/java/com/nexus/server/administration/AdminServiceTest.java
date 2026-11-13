package com.nexus.server.administration;

import com.nexus.server.LikePatterns;
import com.nexus.server.identity.IdentityService;
import com.nexus.server.identity.PasswordResetService;
import com.nexus.server.identity.SessionIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AdminService admin = new AdminService(jdbc, mock(IdentityService.class),
            mock(PasswordResetService.class));

    @Test
    void rejectsNonAdminBeforeReadingAccountData() {
        assertThatThrownBy(() -> admin.users(new SessionIdentity(UUID.randomUUID(), "user"),
                null, null, "name", "asc", null, 50)).isInstanceOf(AdminForbiddenException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNonAdminForEveryAdministrativeOperation() {
        SessionIdentity user = new SessionIdentity(UUID.randomUUID(), "user");
        Instant now = Instant.now();
        assertThatThrownBy(() -> admin.registrations(user, now, now.plusSeconds(60), null, "name", "asc",
                null, 50)).isInstanceOf(AdminForbiddenException.class);
        assertThatThrownBy(() -> admin.reports(user, null, null, null, "createdAt", "desc", null, 50))
                .isInstanceOf(AdminForbiddenException.class);
        assertThatThrownBy(() -> admin.auditEvents(user, null, 50)).isInstanceOf(AdminForbiddenException.class);
        assertThatThrownBy(() -> admin.deleteUser(user, UUID.randomUUID()))
                .isInstanceOf(AdminForbiddenException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsAReversedRegistrationRangeBeforeQuerying() {
        SessionIdentity actor = new SessionIdentity(UUID.randomUUID(), "admin");
        Instant now = Instant.now();
        assertThatThrownBy(() -> admin.registrations(actor, now, now, null, "name", "asc", null, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.registrations(actor, now, now.minusSeconds(1), null, "name", "asc",
                null, 50)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnsupportedAccountSortAndStatusBeforeQuerying() {
        SessionIdentity actor = new SessionIdentity(UUID.randomUUID(), "admin");
        assertThatThrownBy(() -> admin.users(actor, null, null, "email", "asc", null, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.users(actor, null, "deleted", "name", "asc", null, 50))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void groupFilterUsesTheIndexedLikePredicate() {
        assertThat(AdminSupport.groupPageSql("createdAt", "ASC", true, false))
                .as("the filtered group list must use the trigram-backed predicate, not POSITION")
                .contains("lower(c.name) LIKE ? ESCAPE '\\'")
                .doesNotContain("position(");
        assertThat(AdminSupport.groupPageSql("createdAt", "ASC", true, true))
                .as("continuing a search keeps the same predicate")
                .contains("lower(c.name) LIKE ? ESCAPE '\\'")
                .contains("(c.created_at, c.id) > (?, ?)");
        assertThat(AdminSupport.groupPageSql("createdAt", "ASC", false, false))
                .as("a blank or absent term selects the statement without a predicate")
                .doesNotContain("LIKE");
        assertThat(LikePatterns.containing("  Tea % "))
                .as("the term is lower-cased and its wildcard is escaped")
                .isEqualTo("%tea \\%%");
    }

    @Test
    void doesNotAllowAnAdministratorToLockTheirOwnSession() {
        UUID adminId = UUID.randomUUID();
        assertThatThrownBy(() -> admin.setUserLock(new SessionIdentity(adminId, "admin"), adminId,
                new AdminUserLockRequest(true, "self lock"))).isInstanceOf(AdminForbiddenException.class);
        verifyNoInteractions(jdbc);
    }
}
