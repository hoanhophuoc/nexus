package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.identity.UserNotFoundException;
import com.haichutieu.chatsystem.server.messaging.ConversationMemberView;
import com.haichutieu.chatsystem.server.messaging.ConversationPage;
import com.haichutieu.chatsystem.server.messaging.ConversationView;
import com.haichutieu.chatsystem.server.social.SocialUser;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public JdbcGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversationPage list(UUID userId, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND c.id > ?";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        List<UUID> ids = jdbc.query("""
                SELECT c.id FROM chat_v1.conversation c
                JOIN chat_v1.conversation_member m ON m.conversation_id = c.id
                WHERE c.type = 'group' AND m.user_id = ? AND m.left_at IS NULL
                """ + cursorClause + " ORDER BY c.id LIMIT ?",
                (rs, row) -> rs.getObject("id", UUID.class), args.toArray());
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
                INSERT INTO chat_v1.conversation (id, type, name, created_by)
                VALUES (?, 'group', ?, ?)
                """, groupId, name, creatorId);
        jdbc.update("""
                INSERT INTO chat_v1.conversation_member (conversation_id, user_id, role)
                VALUES (?, ?, 'admin')
                """, groupId, creatorId);
        jdbc.batchUpdate("""
                INSERT INTO chat_v1.conversation_member (conversation_id, user_id, role)
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
        jdbc.update("UPDATE chat_v1.conversation SET name = ? WHERE id = ?", name, groupId);
    }

    @Override
    public GroupMemberPage members(UUID actorId, UUID groupId, UUID cursor, int limit) {
        requireMember(actorId, groupId);
        String cursorClause = cursor == null ? "" : "AND u.id > ?";
        List<Object> args = new ArrayList<>(List.of(groupId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        List<ConversationMemberView> rows = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender, m.role, m.joined_epoch
                FROM chat_v1.conversation_member m
                JOIN chat_v1.user_account u ON u.id = m.user_id
                WHERE m.conversation_id = ? AND m.left_at IS NULL
                """ + cursorClause + " ORDER BY u.id LIMIT ?",
                JdbcGroupRepository::mapMember, args.toArray());
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
                SELECT EXISTS (SELECT 1 FROM chat_v1.conversation_member
                               WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL)
                """, Boolean.class, groupId, userId));
        if (active) throw new GroupConflictException();
        lockGroupUsers(groupId, userId);
        requireUnlocked(actorId, userId);
        if (!eligibleToInvite(actorId, userId)) throw new GroupForbiddenException();
        if (blockedByGroupMember(groupId, userId)) throw new GroupForbiddenException();
        int rejoined = jdbc.update("""
                UPDATE chat_v1.conversation_member
                SET role = 'member', joined_at = clock_timestamp(), left_at = NULL,
                    joined_epoch = (SELECT current_epoch + 1 FROM chat_v1.conversation WHERE id = ?),
                    history_cleared_sequence = (SELECT next_message_sequence
                                                FROM chat_v1.conversation WHERE id = ?)
                WHERE conversation_id = ? AND user_id = ?
                """, groupId, groupId, groupId, userId);
        if (rejoined == 0) {
            jdbc.update("""
                    INSERT INTO chat_v1.conversation_member (conversation_id, user_id, role, joined_epoch, history_cleared_sequence)
                    VALUES (?, ?, 'member', (SELECT current_epoch + 1 FROM chat_v1.conversation WHERE id = ?),
                            (SELECT next_message_sequence FROM chat_v1.conversation WHERE id = ?))
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
                UPDATE chat_v1.conversation_member SET left_at = clock_timestamp()
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
                UPDATE chat_v1.conversation_member SET role = ?
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, newRole, groupId, userId);
    }

    private ConversationView conversation(UUID groupId) {
        var head = jdbc.query("""
                SELECT id, name, created_at FROM chat_v1.conversation WHERE id = ? AND type = 'group'
                """, (rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant()}, groupId)
                .stream().findFirst().orElseThrow(UserNotFoundException::new);
        List<ConversationMemberView> members = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender, m.role, m.joined_epoch
                FROM chat_v1.conversation_member m
                JOIN chat_v1.user_account u ON u.id = m.user_id
                WHERE m.conversation_id = ? AND m.left_at IS NULL ORDER BY u.id
                """, JdbcGroupRepository::mapMember, groupId);
        return new ConversationView((UUID) head[0], (String) head[1], "group",
                (java.time.Instant) head[2], members);
    }

    private void lockGroup(UUID groupId) {
        List<UUID> found = jdbc.query("""
                SELECT id FROM chat_v1.conversation WHERE id = ? AND type = 'group' FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), groupId);
        if (found.isEmpty()) throw new UserNotFoundException();
    }

    private String requireMember(UUID userId, UUID groupId) {
        return jdbc.query("""
                SELECT role FROM chat_v1.conversation_member
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, (rs, row) -> rs.getString("role"), groupId, userId)
                .stream().findFirst().orElseThrow(UserNotFoundException::new);
    }

    private void requireAdmin(UUID userId, UUID groupId) {
        if (!"admin".equals(requireMember(userId, groupId))) throw new GroupForbiddenException();
    }

    private String role(UUID groupId, UUID userId) {
        return jdbc.query("""
                SELECT role FROM chat_v1.conversation_member
                WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
                """, (rs, row) -> rs.getString("role"), groupId, userId).stream().findFirst().orElse(null);
    }

    private boolean eligibleToInvite(UUID actorId, UUID targetId) {
        UUID low = actorId.toString().compareTo(targetId.toString()) < 0 ? actorId : targetId;
        UUID high = actorId.toString().compareTo(targetId.toString()) < 0 ? targetId : actorId;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.friendship WHERE user_low_id = ? AND user_high_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM chat_v1.user_block
                    WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, low, high, actorId, targetId, targetId, actorId));
    }

    private boolean blockedAmong(List<UUID> userIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        List<Object> args = new ArrayList<>(userIds);
        args.addAll(userIds);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.user_block
                    WHERE blocker_id IN (%1$s) AND blocked_id IN (%1$s))
                """.formatted(placeholders), Boolean.class, args.toArray()));
    }

    private boolean blockedByGroupMember(UUID groupId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM chat_v1.user_block b
                    JOIN chat_v1.conversation_member m ON m.conversation_id = ? AND m.left_at IS NULL
                    WHERE (b.blocker_id = ? AND b.blocked_id = m.user_id)
                       OR (b.blocked_id = ? AND b.blocker_id = m.user_id))
                """, Boolean.class, groupId, userId, userId));
    }

    private void lockGroupUsers(UUID groupId, UUID invitedUserId) {
        jdbc.query("""
                SELECT id FROM chat_v1.user_account
                WHERE id = ? OR id IN (SELECT user_id FROM chat_v1.conversation_member
                                       WHERE conversation_id = ? AND left_at IS NULL)
                ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), invitedUserId, groupId);
    }

    private void requireUnlocked(UUID firstId, UUID secondId) {
        int active = jdbc.queryForObject("""
                SELECT count(*) FROM chat_v1.user_account
                WHERE id IN (?, ?) AND locked = FALSE
                """, Integer.class, firstId, secondId);
        if (active != 2) throw new UserNotFoundException();
    }

    private void lockUsers(List<UUID> userIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        List<UUID> locked = jdbc.queryForList("""
                SELECT id FROM chat_v1.user_account WHERE id IN (%s) AND locked = FALSE ORDER BY id FOR UPDATE
                """.formatted(placeholders), UUID.class, userIds.toArray());
        if (locked.size() != userIds.size()) throw new UserNotFoundException();
    }

    private int activeAdminCount(UUID groupId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM chat_v1.conversation_member
                WHERE conversation_id = ? AND left_at IS NULL AND role = 'admin'
                """, Integer.class, groupId);
    }

    private int activeMemberCount(UUID groupId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM chat_v1.conversation_member
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
