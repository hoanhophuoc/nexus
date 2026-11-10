package com.haichutieu.chatsystem.server.messaging;

import com.haichutieu.chatsystem.server.identity.UserNotFoundException;
import com.haichutieu.chatsystem.server.social.SocialUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcMessagingRepository implements MessagingRepository {

    private final JdbcTemplate jdbc;

    public JdbcMessagingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ConversationView createDirectConversation(UUID creatorId, UUID participantId) {
        lockUsers(creatorId, participantId);
        UUID[] pair = pair(creatorId, participantId);
        boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.friendship WHERE user_low_id = ? AND user_high_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM chat_v1.user_block
                    WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, pair[0], pair[1], creatorId, participantId, participantId, creatorId));
        if (!eligible) throw new ConversationForbiddenException();

        UUID conversationId = jdbc.query("""
                SELECT id FROM chat_v1.conversation
                WHERE type = 'direct' AND direct_low_id = ? AND direct_high_id = ?
                """, (rs, row) -> rs.getObject("id", UUID.class), pair[0], pair[1])
                .stream().findFirst().orElse(null);
        if (conversationId == null) {
            conversationId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_v1.conversation (id, type, created_by, direct_low_id, direct_high_id)
                    VALUES (?, 'direct', ?, ?, ?)
                    """, conversationId, creatorId, pair[0], pair[1]);
            jdbc.update("INSERT INTO chat_v1.conversation_member (conversation_id, user_id) VALUES (?, ?)",
                    conversationId, creatorId);
            jdbc.update("INSERT INTO chat_v1.conversation_member (conversation_id, user_id) VALUES (?, ?)",
                    conversationId, participantId);
        }
        return conversation(conversationId);
    }

    @Override
    public ConversationPage conversations(UUID userId, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND c.id > ?";
        String sql = """
                SELECT c.id FROM chat_v1.conversation c
                JOIN chat_v1.conversation_member m ON m.conversation_id = c.id
                WHERE m.user_id = ? AND m.left_at IS NULL
                """ + cursorClause + " ORDER BY c.id LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        List<UUID> ids = jdbc.query(sql, (rs, row) -> rs.getObject("id", UUID.class), args.toArray());
        boolean more = ids.size() > limit;
        List<UUID> pageIds = more ? ids.subList(0, limit) : ids;
        List<ConversationView> items = pageIds.stream().map(this::conversation).toList();
        return new ConversationPage(items, more ? pageIds.getLast().toString() : null);
    }

    @Override
    public MessagePage messages(UUID userId, UUID conversationId, UUID after, int limit) {
        requireDirectConversation(userId, conversationId);
        Long cursorSequence = null;
        if (after != null) {
            cursorSequence = jdbc.query("""
                    SELECT conversation_sequence FROM chat_v1.message WHERE conversation_id = ? AND id = ?
                    """, (rs, row) -> rs.getLong("conversation_sequence"), conversationId, after)
                    .stream().findFirst().orElseThrow(UserNotFoundException::new);
        }
        String afterClause = after == null ? "" : "AND m.conversation_sequence > ?";
        String sql = """
                SELECT m.id, m.conversation_id, m.sender_id, m.content, m.created_at,
                       m.deleted_for_everyone_at
                FROM chat_v1.message m
                JOIN chat_v1.conversation_member cm ON cm.conversation_id = m.conversation_id
                WHERE cm.conversation_id = ? AND cm.user_id = ? AND cm.left_at IS NULL
                  AND m.conversation_sequence > cm.history_cleared_sequence
                  AND NOT EXISTS (SELECT 1 FROM chat_v1.message_hidden_for_user h
                                  WHERE h.message_id = m.id AND h.user_id = cm.user_id)
                """ + afterClause + " ORDER BY m.conversation_sequence LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(conversationId, userId));
        if (after != null) {
            args.add(cursorSequence);
        }
        args.add(limit + 1);
        List<MessageView> messages = jdbc.query(sql, JdbcMessagingRepository::mapMessage, args.toArray());
        boolean more = messages.size() > limit;
        List<MessageView> items = more ? messages.subList(0, limit) : messages;
        return new MessagePage(List.copyOf(items), more ? items.getLast().id().toString() : null);
    }

    @Override
    @Transactional
    public MessageView sendMessage(UUID userId, UUID conversationId, String idempotencyKey, String content) {
        String type = lockConversation(conversationId);
        requireMember(userId, conversationId);
        if (!"direct".equals(type)) throw new ConversationForbiddenException();
        List<MessageView> previous = jdbc.query("""
                SELECT id, conversation_id, sender_id, content, created_at, deleted_for_everyone_at
                FROM chat_v1.message
                WHERE conversation_id = ? AND sender_id = ? AND idempotency_key = ?
                """, JdbcMessagingRepository::mapMessage, conversationId, userId, idempotencyKey);
        if (!previous.isEmpty()) {
            MessageView existing = previous.getFirst();
            if (!existing.content().equals(content)) throw new IdempotencyConflictException();
            return existing;
        }
        long sequence = jdbc.queryForObject("""
                UPDATE chat_v1.conversation SET next_message_sequence = next_message_sequence + 1
                WHERE id = ? RETURNING next_message_sequence
                """, Long.class, conversationId);
        List<MessageView> inserted = jdbc.query("""
                INSERT INTO chat_v1.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, conversation_id, sender_id, content, created_at, deleted_for_everyone_at
                """, JdbcMessagingRepository::mapMessage, UUID.randomUUID(), conversationId, userId, sequence,
                idempotencyKey, content);
        MessageView message = inserted.getFirst();
        jdbc.update("""
                INSERT INTO chat_v1.message_outbox (event_id, conversation_id, message_id, event_type)
                VALUES (?, ?, ?, 'message.created')
                """, UUID.randomUUID(), conversationId, message.id());
        return message;
    }

    @Override
    @Transactional
    public void deleteMessage(UUID userId, UUID conversationId, UUID messageId, MessageDeletionScope scope) {
        lockConversation(conversationId);
        requireMember(userId, conversationId);
        UUID senderId = jdbc.query("""
                SELECT sender_id FROM chat_v1.message WHERE conversation_id = ? AND id = ?
                """, (rs, row) -> rs.getObject("sender_id", UUID.class), conversationId, messageId)
                .stream().findFirst().orElseThrow(UserNotFoundException::new);
        if (scope == MessageDeletionScope.everyone) {
            if (!senderId.equals(userId)) throw new MessageDeletionForbiddenException();
            int changed = jdbc.update("""
                    UPDATE chat_v1.message SET content = '', deleted_for_everyone_at = clock_timestamp()
                    WHERE conversation_id = ? AND id = ? AND deleted_for_everyone_at IS NULL
                    """, conversationId, messageId);
            if (changed > 0) {
                jdbc.update("""
                        INSERT INTO chat_v1.message_outbox (event_id, conversation_id, message_id, event_type)
                        VALUES (?, ?, ?, 'message.deleted')
                        """, UUID.randomUUID(), conversationId, messageId);
            }
        } else {
            int changed = jdbc.update("""
                    INSERT INTO chat_v1.message_hidden_for_user (message_id, user_id)
                    VALUES (?, ?) ON CONFLICT DO NOTHING
                    """, messageId, userId);
            if (changed > 0) {
                jdbc.update("""
                        INSERT INTO chat_v1.message_outbox
                            (event_id, conversation_id, message_id, recipient_user_id, event_type)
                        VALUES (?, ?, ?, ?, 'message.hidden')
                        """, UUID.randomUUID(), conversationId, messageId, userId);
            }
        }
    }

    @Override
    @Transactional
    public void clearHistory(UUID userId, UUID conversationId) {
        lockConversation(conversationId);
        int changed = jdbc.update("""
                UPDATE chat_v1.conversation_member m
                SET history_cleared_sequence = c.next_message_sequence
                FROM chat_v1.conversation c
                WHERE m.conversation_id = c.id AND m.conversation_id = ?
                  AND m.user_id = ? AND m.left_at IS NULL
                """, conversationId, userId);
        if (changed == 0) throw new UserNotFoundException();
    }

    private String lockConversation(UUID conversationId) {
        List<String> types = jdbc.query("""
                SELECT type FROM chat_v1.conversation WHERE id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("type"), conversationId);
        if (types.isEmpty()) throw new UserNotFoundException();
        return types.getFirst();
    }

    private void requireDirectConversation(UUID userId, UUID conversationId) {
        List<String> types = jdbc.query("""
                SELECT c.type FROM chat_v1.conversation c
                JOIN chat_v1.conversation_member m ON m.conversation_id = c.id
                WHERE c.id = ? AND m.user_id = ? AND m.left_at IS NULL
                """, (rs, row) -> rs.getString("type"), conversationId, userId);
        if (types.isEmpty()) throw new UserNotFoundException();
        if (!"direct".equals(types.getFirst())) throw new ConversationForbiddenException();
    }

    private void lockUsers(UUID firstId, UUID secondId) {
        List<UUID> locked = jdbc.query("""
                SELECT id FROM chat_v1.user_account WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), firstId, secondId);
        if (locked.size() != 2) throw new UserNotFoundException();
    }

    private void requireMember(UUID userId, UUID conversationId) {
        boolean member = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.conversation_member
                               WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL)
                """, Boolean.class, conversationId, userId));
        if (!member) throw new UserNotFoundException();
    }

    private ConversationView conversation(UUID conversationId) {
        var header = jdbc.query("""
                SELECT id, name, type, created_at FROM chat_v1.conversation WHERE id = ?
                """, (rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("type"), rs.getTimestamp("created_at").toInstant()}, conversationId)
                .stream().findFirst().orElseThrow(UserNotFoundException::new);
        List<ConversationMemberView> members = jdbc.query("""
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender, m.role, m.joined_epoch
                FROM chat_v1.conversation_member m JOIN chat_v1.user_account u ON u.id = m.user_id
                WHERE m.conversation_id = ? AND m.left_at IS NULL ORDER BY u.id
                """, (rs, row) -> new ConversationMemberView(mapUser(rs), rs.getString("role"), rs.getInt("joined_epoch")), conversationId);
        return new ConversationView((UUID) header[0], (String) header[1], (String) header[2],
                (Instant) header[3], members);
    }

    private static UUID[] pair(UUID firstId, UUID secondId) {
        return firstId.toString().compareTo(secondId.toString()) < 0
                ? new UUID[]{firstId, secondId} : new UUID[]{secondId, firstId};
    }

    private static SocialUser mapUser(ResultSet rs) throws SQLException {
        return new SocialUser(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("full_name"),
                rs.getString("address"), rs.getObject("birth_date", LocalDate.class), rs.getString("gender"), "offline");
    }

    private static MessageView mapMessage(ResultSet rs, int row) throws SQLException {
        return new MessageView(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getObject("sender_id", UUID.class), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("deleted_for_everyone_at") == null
                ? null : rs.getTimestamp("deleted_for_everyone_at").toInstant());
    }
}
