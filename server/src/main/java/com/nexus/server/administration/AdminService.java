package com.nexus.server.administration;

import com.nexus.server.groups.GroupMemberPage;
import com.nexus.server.identity.IdentityService;
import com.nexus.server.identity.PasswordResetService;
import com.nexus.server.identity.ProfileUpdateRequest;
import com.nexus.server.identity.RegistrationRequest;
import com.nexus.server.identity.SessionIdentity;
import com.nexus.server.identity.UserProfile;
import com.nexus.server.messaging.ConversationMemberView;
import com.nexus.server.messaging.ConversationPage;
import com.nexus.server.messaging.ConversationView;
import com.nexus.server.social.SocialUser;
import com.nexus.server.social.UserPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminService {
    private static final String USER_COLUMNS =
            "u.id, u.username, u.full_name, u.email, u.address, u.birth_date, u.gender, u.locked, u.created_at";

    private final JdbcTemplate jdbc;
    private final IdentityService identity;
    private final PasswordResetService passwordResets;

    public AdminService(JdbcTemplate jdbc, IdentityService identity, PasswordResetService passwordResets) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.passwordResets = passwordResets;
    }

    public AdminUserPage users(SessionIdentity actor, String query, String status, String sort, String direction,
                               String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        if (status != null && !status.equals("active") && !status.equals("locked")) {
            throw new IllegalArgumentException("Unsupported account status.");
        }
        return accountPage(query, status, null, null, sort, direction, cursor, limit);
    }

    public AdminUserPage registrations(SessionIdentity actor, Instant from, Instant to, String query, String sort,
                                       String direction, String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        AdminSupport.requireRange(from, to);
        return accountPage(query, null, from, to, sort, direction, cursor, limit);
    }

    public AdminUserView user(SessionIdentity actor, UUID userId) {
        AdminSupport.requireAdmin(actor);
        return jdbc.query("SELECT " + USER_COLUMNS + " FROM nexus.user_account u WHERE u.id = ?",
                        AdminSupport::mapUser, userId)
                .stream().findFirst()
                .orElseThrow(() -> new AdminNotFoundException("Account not found."));
    }

    @Transactional
    public AdminUserView createUser(SessionIdentity actor, RegistrationRequest request) {
        AdminSupport.requireAdmin(actor);
        UserProfile created = identity.register(request);
        audit(actor.userId(), "user.create", created.id(), "username", created.username());
        return user(actor, created.id());
    }

    @Transactional
    public AdminUserView updateUser(SessionIdentity actor, UUID userId, ProfileUpdateRequest request) {
        AdminSupport.requireAdmin(actor);
        requireAccount(userId);
        identity.updateProfile(userId, request);
        audit(actor.userId(), "user.update", userId, "reason", "administrator updated the profile");
        return user(actor, userId);
    }

    @Transactional
    public void deleteUser(SessionIdentity actor, UUID userId) {
        AdminSupport.requireAdmin(actor);
        if (actor.userId().equals(userId)) {
            throw new AdminForbiddenException();
        }
        List<String> roles = jdbc.query("""
                SELECT role FROM nexus.user_account WHERE id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), userId);
        if (roles.isEmpty()) {
            throw new AdminNotFoundException("Account not found.");
        }
        if ("admin".equals(roles.getFirst()) && otherAdmins(userId) == 0) {
            throw new AdminForbiddenException();
        }
        audit(actor.userId(), "user.delete", userId, "reason", "administrator deleted the account");
        jdbc.update("DELETE FROM nexus.user_account WHERE id = ?", userId);
    }

    @Transactional
    public void setUserLock(SessionIdentity actor, UUID userId, AdminUserLockRequest request) {
        AdminSupport.requireAdmin(actor);
        if (changeLock(actor, userId, request.locked())) {
            audit(actor.userId(), request.locked() ? "user.lock" : "user.unlock", userId,
                    "reason", request.reason().strip());
        }
    }

    @Transactional
    public void requestPasswordReset(SessionIdentity actor, UUID userId) {
        AdminSupport.requireAdmin(actor);
        requireAccount(userId);
        passwordResets.requestForUser(userId);
        audit(actor.userId(), "user.password-reset", userId, "reason", "administrator requested a reset");
    }

    public LoginHistoryPage loginHistory(SessionIdentity actor, UUID userId, String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        requireAccount(userId);
        return loginHistoryPage(userId, cursor, limit);
    }

    public LoginHistoryPage allLoginHistory(SessionIdentity actor, String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        return loginHistoryPage(null, cursor, limit);
    }

    public UserPage<SocialUser> userFriends(SessionIdentity actor, UUID userId, UUID cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        requireAccount(userId);
        int pageSize = Math.clamp(limit, 1, 100);
        String cursorClause = cursor == null ? "" : "AND u.id > ?";
        List<Object> args = new ArrayList<>(List.of(userId, userId, userId));
        if (cursor != null) {
            args.add(cursor);
        }
        args.add(pageSize + 1);
        List<SocialUser> found = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender
                FROM nexus.friendship f
                JOIN nexus.user_account u
                  ON u.id = CASE WHEN f.user_low_id = ? THEN f.user_high_id ELSE f.user_low_id END
                WHERE (f.user_low_id = ? OR f.user_high_id = ?)
                """ + cursorClause + " ORDER BY u.id LIMIT ?",
                (rs, row) -> new SocialUser(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("full_name"), rs.getString("address"),
                        rs.getObject("birth_date", LocalDate.class), rs.getString("gender"), "offline"),
                args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<SocialUser> items = List.copyOf(found.subList(0, Math.min(found.size(), pageSize)));
        return new UserPage<>(items, hasMore ? items.getLast().id().toString() : null);
    }

    public ConversationPage groups(SessionIdentity actor, String query, String sort, String direction,
                                   String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        String sortField = sort == null ? "createdAt" : sort;
        String order = switch (sortField) {
            case "name" -> "lower(c.name)";
            case "createdAt" -> "c.created_at";
            default -> throw new IllegalArgumentException("Unsupported group sort field.");
        };
        String orderDirection = AdminSupport.orderDirection(direction);
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE c.type = 'group'");
        if (query != null && !query.isBlank()) {
            where.append(" AND position(lower(?) in lower(c.name)) > 0");
            args.add(query.strip());
        }
        if (cursor != null) {
            AdminSupport.Cursor decoded = AdminSupport.decodeCursor(cursor);
            where.append(" AND (").append(order).append(", c.id) ")
                    .append("ASC".equals(orderDirection) ? ">" : "<").append(" (?, ?)");
            args.add("createdAt".equals(sortField) ? Timestamp.from(AdminSupport.cursorInstant(decoded.sortKey()))
                    : decoded.sortKey());
            args.add(decoded.id());
        }
        args.add(pageSize + 1);
        List<GroupRow> found = jdbc.query("SELECT c.id, c.name, c.created_at FROM nexus.conversation c "
                        + where + " ORDER BY " + order + " " + orderDirection + ", c.id " + orderDirection
                        + " LIMIT ?",
                (rs, row) -> new GroupRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant()), args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<GroupRow> page = found.subList(0, Math.min(found.size(), pageSize));
        String next = hasMore ? AdminSupport.encodeCursor(page.getLast().id(),
                "createdAt".equals(sortField) ? page.getLast().createdAt().toString()
                        : page.getLast().name().toLowerCase(Locale.ROOT)) : null;
        return new ConversationPage(page.stream().map(group -> new ConversationView(group.id(), group.name(),
                "group", group.createdAt(), List.of())).toList(), next);
    }

    public GroupMemberPage groupMembers(SessionIdentity actor, UUID groupId, UUID cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM nexus.conversation WHERE id = ? AND type = 'group')
                """, Boolean.class, groupId));
        if (!exists) {
            throw new AdminNotFoundException("Group not found.");
        }
        int pageSize = Math.clamp(limit, 1, 100);
        String cursorClause = cursor == null ? "" : "AND u.id > ?";
        List<Object> args = new ArrayList<>(List.of(groupId));
        if (cursor != null) {
            args.add(cursor);
        }
        args.add(pageSize + 1);
        List<ConversationMemberView> found = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender, m.role, m.joined_epoch
                FROM nexus.conversation_member m
                JOIN nexus.user_account u ON u.id = m.user_id
                WHERE m.conversation_id = ? AND m.left_at IS NULL
                """ + cursorClause + " ORDER BY u.id LIMIT ?", AdminService::mapMember, args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<ConversationMemberView> items = List.copyOf(found.subList(0, Math.min(found.size(), pageSize)));
        return new GroupMemberPage(items, hasMore ? items.getLast().user().id().toString() : null);
    }

    public ReportPage reports(SessionIdentity actor, String reportedUsername, Instant from, Instant to,
                              String sort, String direction, String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        if (from != null && to != null) {
            AdminSupport.requireRange(from, to);
        }
        String sortField = sort == null ? "createdAt" : sort;
        String order = switch (sortField) {
            case "createdAt" -> "r.created_at";
            case "reportedUsername" -> "lower(tgt.username)";
            default -> throw new IllegalArgumentException("Unsupported report sort field.");
        };
        String orderDirection = AdminSupport.orderDirection(direction);
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE TRUE");
        if (reportedUsername != null && !reportedUsername.isBlank()) {
            where.append(" AND lower(tgt.username) = lower(?)");
            args.add(reportedUsername.strip());
        }
        if (from != null) {
            where.append(" AND r.created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND r.created_at < ?");
            args.add(Timestamp.from(to));
        }
        if (cursor != null) {
            AdminSupport.Cursor decoded = AdminSupport.decodeCursor(cursor);
            where.append(" AND (").append(order).append(", r.id) ")
                    .append("ASC".equals(orderDirection) ? ">" : "<").append(" (?, ?)");
            args.add("createdAt".equals(sortField) ? Timestamp.from(AdminSupport.cursorInstant(decoded.sortKey()))
                    : decoded.sortKey());
            args.add(decoded.id());
        }
        args.add(pageSize + 1);
        List<Report> found = jdbc.query("""
                SELECT r.id, r.reason, r.status, r.created_at,
                       rep.id AS reporter_id, rep.username AS reporter_username,
                       rep.full_name AS reporter_full_name, rep.address AS reporter_address,
                       rep.birth_date AS reporter_birth_date, rep.gender AS reporter_gender,
                       tgt.id AS reported_id, tgt.username AS reported_username,
                       tgt.full_name AS reported_full_name, tgt.address AS reported_address,
                       tgt.birth_date AS reported_birth_date, tgt.gender AS reported_gender
                FROM nexus.spam_report r
                JOIN nexus.user_account rep ON rep.id = r.reporter_id
                JOIN nexus.user_account tgt ON tgt.id = r.reported_id
                """ + where + " ORDER BY " + order + " " + orderDirection + ", r.id " + orderDirection
                + " LIMIT ?", AdminService::mapReport, args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<Report> page = found.subList(0, Math.min(found.size(), pageSize));
        Map<UUID, List<ReportEvidence>> evidence = evidence(page.stream().map(Report::id).toList());
        List<Report> items = page.stream().map(report -> new Report(report.id(), report.reporter(),
                report.reportedUser(), report.reason(), report.createdAt(), report.status(),
                evidence.getOrDefault(report.id(), List.of()))).toList();
        String next = hasMore ? AdminSupport.encodeCursor(page.getLast().id(),
                "createdAt".equals(sortField) ? page.getLast().createdAt().toString()
                        : page.getLast().reportedUser().username().toLowerCase(Locale.ROOT)) : null;
        return new ReportPage(items, next);
    }

    @Transactional
    public void decideReport(SessionIdentity actor, UUID reportId, AdminReportDecisionRequest request) {
        AdminSupport.requireAdmin(actor);
        List<ReportState> states = jdbc.query("""
                SELECT status, reported_id FROM nexus.spam_report WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ReportState(rs.getString("status"),
                rs.getObject("reported_id", UUID.class)), reportId);
        if (states.isEmpty()) {
            throw new AdminNotFoundException("Report not found.");
        }
        ReportState state = states.getFirst();
        if (!"pending".equals(state.status())) {
            throw new AdminConflictException("The report has already been decided.");
        }
        String status = "uphold".equals(request.decision()) ? "upheld" : "dismissed";
        jdbc.update("""
                UPDATE nexus.spam_report
                SET status = ?, reviewed_by = ?, reviewed_at = clock_timestamp()
                WHERE id = ?
                """, status, actor.userId(), reportId);
        String note = request.note() == null ? "" : request.note().strip();
        jdbc.update("""
                INSERT INTO nexus.moderation_action (id, moderator_id, report_id, target_user_id, action, details)
                VALUES (?, ?, ?, ?, ?, jsonb_build_object('note', ?))
                """, UUID.randomUUID(), actor.userId(), reportId, state.reportedId(),
                "report." + request.decision(), note);
        if (request.lockReportedUser() && changeLock(actor, state.reportedId(), true)) {
            audit(actor.userId(), "user.lock", state.reportedId(), "reason", "Report " + reportId + " upheld");
        }
        audit(actor.userId(), "report." + request.decision(), reportId, "note", note);
    }

    public AuditEventPage auditEvents(SessionIdentity actor, String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE TRUE");
        if (cursor != null) {
            AdminSupport.Cursor decoded = AdminSupport.decodeCursor(cursor);
            where.append(" AND (occurred_at, id) < (?, ?)");
            args.add(Timestamp.from(AdminSupport.cursorInstant(decoded.sortKey())));
            args.add(decoded.id());
        }
        args.add(pageSize + 1);
        List<AuditEvent> found = jdbc.query("""
                SELECT id, actor_id, action, target_id, occurred_at FROM nexus.admin_audit_event
                """ + where + " ORDER BY occurred_at DESC, id DESC LIMIT ?",
                (rs, row) -> new AuditEvent(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("action"),
                        rs.getObject("target_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()), args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<AuditEvent> items = List.copyOf(found.subList(0, Math.min(found.size(), pageSize)));
        return new AuditEventPage(items, hasMore
                ? AdminSupport.encodeCursor(items.getLast().id(), items.getLast().occurredAt().toString()) : null);
    }

    private AdminUserPage accountPage(String query, String status, Instant from, Instant to, String sort,
                                      String direction, String cursor, int limit) {
        String sortField = sort == null ? "createdAt" : sort;
        String order = switch (sortField) {
            case "name" -> "lower(u.full_name)";
            case "createdAt" -> "u.created_at";
            default -> throw new IllegalArgumentException("Unsupported account sort field.");
        };
        String orderDirection = AdminSupport.orderDirection(direction);
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE TRUE");
        AdminSupport.appendUserFilter(where, args, query);
        if (status != null) {
            where.append(" AND u.locked = ?");
            args.add(status.equals("locked"));
        }
        if (from != null) {
            where.append(" AND u.created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND u.created_at < ?");
            args.add(Timestamp.from(to));
        }
        if (cursor != null) {
            AdminSupport.Cursor decoded = AdminSupport.decodeCursor(cursor);
            where.append(" AND (").append(order).append(", u.id) ")
                    .append("ASC".equals(orderDirection) ? ">" : "<").append(" (?, ?)");
            args.add("createdAt".equals(sortField) ? Timestamp.from(AdminSupport.cursorInstant(decoded.sortKey()))
                    : decoded.sortKey());
            args.add(decoded.id());
        }
        args.add(pageSize + 1);
        List<AdminUserView> found = jdbc.query("SELECT " + USER_COLUMNS + " FROM nexus.user_account u "
                        + where + " ORDER BY " + order + " " + orderDirection + ", u.id " + orderDirection
                        + " LIMIT ?", AdminSupport::mapUser, args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<AdminUserView> items = List.copyOf(found.subList(0, Math.min(found.size(), pageSize)));
        String next = hasMore
                ? AdminSupport.encodeCursor(items.getLast().id(), AdminSupport.cursorKey(items.getLast(), sortField))
                : null;
        return new AdminUserPage(items, next);
    }

    private LoginHistoryPage loginHistoryPage(UUID userId, String cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE TRUE");
        if (userId != null) {
            where.append(" AND h.user_id = ?");
            args.add(userId);
        }
        if (cursor != null) {
            AdminSupport.Cursor decoded = AdminSupport.decodeCursor(cursor);
            where.append(" AND (h.occurred_at, h.id) < (?, ?)");
            args.add(Timestamp.from(AdminSupport.cursorInstant(decoded.sortKey())));
            args.add(decoded.id());
        }
        args.add(pageSize + 1);
        List<HistoryRow> found = jdbc.query("""
                SELECT h.id, h.user_id, h.occurred_at, u.username, u.full_name
                FROM nexus.login_history h
                JOIN nexus.user_account u ON u.id = h.user_id
                """ + where + " ORDER BY h.occurred_at DESC, h.id DESC LIMIT ?",
                (rs, row) -> new HistoryRow(rs.getObject("id", UUID.class), new LoginHistoryEntry(
                        rs.getObject("user_id", UUID.class), rs.getString("username"),
                        rs.getString("full_name"), rs.getTimestamp("occurred_at").toInstant())), args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<HistoryRow> page = found.subList(0, Math.min(found.size(), pageSize));
        LoginHistoryPage result = new LoginHistoryPage(page.stream().map(HistoryRow::entry).toList(),
                hasMore ? AdminSupport.encodeCursor(page.getLast().rowId(),
                        page.getLast().entry().occurredAt().toString()) : null);
        return result;
    }

    private boolean changeLock(SessionIdentity actor, UUID userId, boolean locked) {
        if (actor.userId().equals(userId)) {
            throw new AdminForbiddenException();
        }
        List<LockState> states = jdbc.query("""
                SELECT locked, role FROM nexus.user_account WHERE id = ? FOR UPDATE
                """, (rs, row) -> new LockState(rs.getBoolean("locked"), rs.getString("role")), userId);
        if (states.isEmpty()) {
            throw new AdminNotFoundException("Account not found.");
        }
        LockState state = states.getFirst();
        if (state.locked() == locked) {
            return false;
        }
        if (locked && "admin".equals(state.role()) && otherAdmins(userId) == 0) {
            throw new AdminForbiddenException();
        }
        jdbc.update("UPDATE nexus.user_account SET locked = ? WHERE id = ?", locked, userId);
        if (locked) {
            jdbc.update("""
                    UPDATE nexus.user_session SET revoked_at = clock_timestamp()
                    WHERE user_id = ? AND revoked_at IS NULL
                    """, userId);
        }
        return true;
    }

    private int otherAdmins(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM nexus.user_account
                WHERE role = 'admin' AND locked = FALSE AND id <> ?
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    private void requireAccount(UUID userId) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM nexus.user_account WHERE id = ?)", Boolean.class, userId));
        if (!exists) {
            throw new AdminNotFoundException("Account not found.");
        }
    }

    private void audit(UUID actorId, String action, UUID targetId, String detailKey, String detailValue) {
        jdbc.update("""
                INSERT INTO nexus.admin_audit_event (id, actor_id, action, target_id, details)
                VALUES (?, ?, ?, ?, jsonb_build_object(?, ?))
                """, UUID.randomUUID(), actorId, action, targetId, detailKey, detailValue);
    }

    private Map<UUID, List<ReportEvidence>> evidence(List<UUID> reportIds) {
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(reportIds.size(), "?"));
        Map<UUID, List<ReportEvidence>> result = new HashMap<>();
        jdbc.query("""
                SELECT report_id, message_id, submitted_content
                FROM nexus.spam_report_evidence
                WHERE report_id IN (""" + placeholders + ")",
                (RowCallbackHandler) rs -> result
                        .computeIfAbsent(rs.getObject("report_id", UUID.class), key -> new ArrayList<>())
                        .add(new ReportEvidence(rs.getObject("message_id", UUID.class),
                                rs.getString("submitted_content"))),
                reportIds.toArray());
        return result;
    }

    private static Report mapReport(ResultSet rs, int row) throws SQLException {
        return new Report(rs.getObject("id", UUID.class), socialUser(rs, "reporter"),
                socialUser(rs, "reported"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("status"), List.of());
    }

    private static SocialUser socialUser(ResultSet rs, String prefix) throws SQLException {
        return new SocialUser(rs.getObject(prefix + "_id", UUID.class), rs.getString(prefix + "_username"),
                rs.getString(prefix + "_full_name"), rs.getString(prefix + "_address"),
                rs.getObject(prefix + "_birth_date", LocalDate.class), rs.getString(prefix + "_gender"),
                "offline");
    }

    private static ConversationMemberView mapMember(ResultSet rs, int row) throws SQLException {
        return new ConversationMemberView(new SocialUser(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("full_name"), rs.getString("address"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("gender"), "offline"),
                rs.getString("role"), rs.getInt("joined_epoch"));
    }

    private record LockState(boolean locked, String role) {
    }

    private record HistoryRow(UUID rowId, LoginHistoryEntry entry) {
    }

    private record GroupRow(UUID id, String name, Instant createdAt) {
    }

    private record ReportState(String status, UUID reportedId) {
    }
}
