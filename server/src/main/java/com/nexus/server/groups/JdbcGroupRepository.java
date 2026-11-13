package com.nexus.server.groups;

import com.nexus.server.identity.UserNotFoundException;
import com.nexus.server.messaging.ConversationMemberView;
import com.nexus.server.messaging.ConversationPage;
import com.nexus.server.messaging.ConversationView;
import com.nexus.server.social.SocialUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGroupRepository implements GroupRepository {

    /**
     * Ids are random UUIDs, so the nil UUID sorts below every real id: binding it on a first page
     * keeps the statement a compile-time constant instead of appending a cursor clause per call.
     */
    private static final UUID FIRST_PAGE = new UUID(0L, 0L);

    static final String LIST_SQL = """
            SELECT c.id FROM nexus.conversation c
            JOIN nexus.conversation_member m ON m.conversation_id = c.id
            WHERE c.type = 'group' AND m.user_id = ? AND m.left_at IS NULL
              AND c.id > ?
            ORDER BY c.id LIMIT ?
            """;

    static final String MEMBERS_SQL = """
            SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender, m.role, m.joined_epoch
            FROM nexus.conversation_member m
            JOIN nexus.user_account u ON u.id = m.user_id
            WHERE m.conversation_id = ? AND m.left_at IS NULL
              AND u.id > ?
            ORDER BY u.id LIMIT ?
            """;

    private static final String LOCK_USERS_SQL = """
            SELECT id FROM nexus.user_account
            WHERE id IN (:ids) AND locked = FALSE
            ORDER BY id FOR UPDATE
            """;

    private static final String BLOCKED_AMONG_SQL = """
            SELECT EXISTS (SELECT 1 FROM nexus.user_block
                WHERE blocker_id IN (:ids) AND blocked_id IN (:ids))
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcGroupRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Override
    public ConversationPage list(UUID userId, UUID cursor, int limit) {
        List<UUID> ids = jdbc.query(LIST_SQL, (rs, row) -> rs.getObject("id", UUID.class),
                userId, cursor == null ? FIRST_PAGE : cursor, limit + 1);
        boolean more = ids.size() > limit;
        List<UUID> page = more ? ids.subList(0, limit) : ids;
        return new ConversationPage(page.stream().map(this::conversation).toList(),
                more ? page.getLast().toString() : null);
    }

    @Override
    @Transactional
    public ConversationView create(UUID creatorId, String name, List<UUID> invitedUserIds) {
        List<UUID> allUsers = new ArrayList<>(invitedUserIds);
        allUsers.add(creatorId);
        lockUsers(allUsers);
        if (blockedAmong(allUsers)) throw new GroupForbiddenException();
        for (UUID invitee : invitedUserIds) {
            if (!eligibleToInvite(creatorId, invitee)) throw new GroupForbiddenException();
        }
        UUID groupId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, name, created_by)
                VALUES (?, 'group', ?, ?)
                """, groupId, name, creatorId);
        jdbc.update("""
                INSERT INTO nexus.conversation_member (conversation_id, user_id, role)
                VALUES (?, ?, 'admin')
                """, groupId, creatorId);
        jdbc.batchUpdate("""
                INSERT INTO nexus.conversation_member (conversation_id, user_id, role)
                VALUES (?, ?, 'member')
                """, invitedUserIds, invitedUserIds.size(), (statement, userId) -> {
            statement.setObject(1, groupId);
            statement.setObject(2, userId);
        });
        return conversation(groupId);
    }

    @Override
    @Transactional
    public void rename(UUID actorId, UUID groupId, String name) {
        lockGroup(groupId);
        requireAdmin(actorId, groupId);
        jdbc.update("UPDATE nexus.conversation SET name = ? WHERE id = ?", name, groupId);
    }

    @Override
    public GroupMemberPage members(UUID actorId, UUID groupId, UUID cursor, int limit) {
        requireMember(actorId, groupId);
        List<ConversationMemberView> rows = jdbc.query(MEMBERS_SQL, JdbcGroupRepository::mapMember,
                groupId, cursor == null ? FIRST_PAGE : cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<ConversationMemberView> page = more ? rows.subList(0, limit) : rows;
        return new GroupMemberPage(List.copyOf(page), more ? page.getLast().user().id().toString() : null);
    }

    @Override
    @Transactional
    public void addMember(UUID actorId, UUID groupId, UUID userId) {
        lockGroup(groupId);
        requireAdmin(actorId, groupId);
        if (actorId.equals(userId)) throw new GroupConflictException();
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM nexus.conversation_member
                               WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL)
                """, Boolean.class, groupId, userId));
        if (active) throw new GroupConflictException();
        lockGroupUsers(groupId, userId);
        requireUnlocked(actorId, userId);
        if (!eligibleToInvite(actorId, userId)) throw new GroupForbiddenException();
        if (blockedByGroupMember(groupId, userId)) throw new GroupForbiddenException();
        int rejoined = jdbc.update("""
                UPDATE nexus.conversation_member
                SET role = 'member', joined_at = clock_timestamp(), left_at = NULL,
                    joined_epoch = (SELECT current_epoch + 1 FROM nexus.conversation WHERE id = ?),
                    history_cleared_sequence = (SELECT next_message_sequence
                                                FROM nexus.conversation WHERE id = ?)
                WHERE conversation_id = ? AND user_id = ?
                """, groupId, groupId, groupId, userId);
        if (rejoined == 0) {
            jdbc.update("""
                    INSERT INTO nexus.conversation_member (conversation_id, user_id, role, joined_epoch, history_cleared_sequence)
                    VALUES (?, ?, 'member', (SELECT current_epoch + 1 FROM nexus.conversation WHERE id = ?),
                            (SELECT next_message_sequence FROM nexus.conversation WHERE id = ?))
                    """, groupId, userId, groupId, groupId);
        }
    }

    @Override
    @Transactional
    public void removeMember(UUID actorId, UUID groupId, UUID userId) {
        lockGroup(groupId);
        String actorRole = requireMember(actorId, groupId);
        String targetRole = role(groupId, userId);
        if (targetRole == null) throw new UserNotFoundException();
        if (!actorId.equals(userId) && !"admin".equals(actorRole)) throw new GroupForbiddenException();
        if ("admin".equals(targetRole) && activeAdminCount(groupId) == 1 && activeMemberCount(groupId) > 1) {
            throw new GroupConflictException();
        }
        jdbc.update("""
                UPDATE nexus.conversation_member SET left_at = clock_timestamp()
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, groupId, userId);
    }

    @Override
    @Transactional
    public void setRole(UUID actorId, UUID groupId, UUID userId, String newRole) {
        lockGroup(groupId);
        requireAdmin(actorId, groupId);
        String oldRole = role(groupId, userId);
        if (oldRole == null) throw new UserNotFoundException();
        if ("admin".equals(oldRole) && "member".equals(newRole) && activeAdminCount(groupId) == 1) {
            throw new GroupConflictException();
        }
        jdbc.update("""
                UPDATE nexus.conversation_member SET role = ?
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, newRole, groupId, userId);
    }

    private ConversationView conversation(UUID groupId) {
        var head = jdbc.query("""
                SELECT id, name, created_at FROM nexus.conversation WHERE id = ? AND type = 'group'
                """, (rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant()}, groupId)
                .stream().findFirst().orElseThrow(UserNotFoundException::new);
        List<ConversationMemberView> members = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender, m.role, m.joined_epoch
                FROM nexus.conversation_member m
                JOIN nexus.user_account u ON u.id = m.user_id
                WHERE m.conversation_id = ? AND m.left_at IS NULL ORDER BY u.id
                """, JdbcGroupRepository::mapMember, groupId);
        return new ConversationView((UUID) head[0], (String) head[1], "group",
                (java.time.Instant) head[2], members);
    }

    private void lockGroup(UUID groupId) {
        List<UUID> found = jdbc.query("""
                SELECT id FROM nexus.conversation WHERE id = ? AND type = 'group' FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), groupId);
        if (found.isEmpty()) throw new UserNotFoundException();
    }

    private String requireMember(UUID userId, UUID groupId) {
        return jdbc.query("""
                SELECT role FROM nexus.conversation_member
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, (rs, row) -> rs.getString("role"), groupId, userId)
                .stream().findFirst().orElseThrow(UserNotFoundException::new);
    }

    private void requireAdmin(UUID userId, UUID groupId) {
        if (!"admin".equals(requireMember(userId, groupId))) throw new GroupForbiddenException();
    }

    private String role(UUID groupId, UUID userId) {
        return jdbc.query("""
                SELECT role FROM nexus.conversation_member
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, (rs, row) -> rs.getString("role"), groupId, userId).stream().findFirst().orElse(null);
    }

    private boolean eligibleToInvite(UUID actorId, UUID targetId) {
        UUID low = actorId.toString().compareTo(targetId.toString()) < 0 ? actorId : targetId;
        UUID high = actorId.toString().compareTo(targetId.toString()) < 0 ? targetId : actorId;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM nexus.friendship WHERE user_low_id = ? AND user_high_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM nexus.user_block
                    WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, low, high, actorId, targetId, targetId, actorId));
    }

    private boolean blockedAmong(List<UUID> userIds) {
        return Boolean.TRUE.equals(named.queryForObject(BLOCKED_AMONG_SQL,
                new MapSqlParameterSource("ids", userIds), Boolean.class));
    }

    private boolean blockedByGroupMember(UUID groupId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM nexus.user_block b
                    JOIN nexus.conversation_member m ON m.conversation_id = ? AND m.left_at IS NULL
                    WHERE (b.blocker_id = ? AND b.blocked_id = m.user_id)
                       OR (b.blocked_id = ? AND b.blocker_id = m.user_id))
                """, Boolean.class, groupId, userId, userId));
    }

    private void lockGroupUsers(UUID groupId, UUID invitedUserId) {
        jdbc.query("""
                SELECT id FROM nexus.user_account
                WHERE id = ? OR id IN (SELECT user_id FROM nexus.conversation_member
                                       WHERE conversation_id = ? AND left_at IS NULL)
                ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), invitedUserId, groupId);
    }

    private void requireUnlocked(UUID firstId, UUID secondId) {
        int active = jdbc.queryForObject("""
                SELECT count(*) FROM nexus.user_account
                WHERE id IN (?, ?) AND locked = FALSE
                """, Integer.class, firstId, secondId);
        if (active != 2) throw new UserNotFoundException();
    }

    private void lockUsers(List<UUID> userIds) {
        List<UUID> locked = named.queryForList(LOCK_USERS_SQL,
                new MapSqlParameterSource("ids", userIds), UUID.class);
        if (locked.size() != userIds.size()) throw new UserNotFoundException();
    }

    private int activeAdminCount(UUID groupId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM nexus.conversation_member
                WHERE conversation_id = ? AND left_at IS NULL AND role = 'admin'
                """, Integer.class, groupId);
    }

    private int activeMemberCount(UUID groupId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM nexus.conversation_member
                WHERE conversation_id = ? AND left_at IS NULL
                """, Integer.class, groupId);
    }

    private static ConversationMemberView mapMember(ResultSet rs, int row) throws SQLException {
        SocialUser user = new SocialUser(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("full_name"), rs.getString("address"), rs.getObject("birth_date", LocalDate.class),
                rs.getString("gender"), "offline");
        return new ConversationMemberView(user, rs.getString("role"), rs.getInt("joined_epoch"));
    }
}
