package com.haichutieu.chatsystem.server.social;

import com.haichutieu.chatsystem.server.LikePatterns;
import com.haichutieu.chatsystem.server.identity.UserNotFoundException;
import org.springframework.dao.DuplicateKeyException;
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
public class JdbcSocialRepository implements SocialRepository {

    private static final org.springframework.jdbc.core.RowMapper<SocialUser> USER_MAPPER = JdbcSocialRepository::mapUser;
    private final JdbcTemplate jdbc;

    public JdbcSocialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Both predicates are substring matches anchored by the trigram indexes from V9, so the search
     * reads the users whose name actually contains the term instead of walking the table in id order
     * until it has enough rows. Wildcards in the term are escaped, so a query for % or _ matches
     * those characters literally instead of matching everything.
     */
    static final String SEARCH_SQL = """
            SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender
            FROM chat_v1.user_account u
            WHERE u.id <> ? AND u.locked = FALSE
              AND (u.username_normalized LIKE ? ESCAPE '\\'
                   OR LOWER(u.full_name) LIKE ? ESCAPE '\\')
              AND NOT EXISTS (
                  SELECT 1 FROM chat_v1.user_block b
                  WHERE (b.blocker_id = ? AND b.blocked_id = u.id)
                     OR (b.blocker_id = u.id AND b.blocked_id = ?)
              )
            """;

    @Override
    public UserPage<SocialUser> search(UUID viewerId, String query, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND u.id > ?";
        String sql = SEARCH_SQL + cursorClause + " ORDER BY u.id LIMIT ?";
        String pattern = LikePatterns.containing(query);
        List<Object> args = new ArrayList<>(List.of(viewerId, pattern, pattern, viewerId, viewerId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        List<SocialUser> users = jdbc.query(sql, USER_MAPPER, args.toArray());
        return page(users, limit);
    }

    @Override
    public FriendRequestPage incomingRequests(UUID userId, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND r.id > ?";
        String sql = """
                SELECT r.id AS request_id, u.id, u.username, u.full_name, u.address, u.birth_date, u.gender
                FROM chat_v1.friend_request r
                JOIN chat_v1.user_account u ON u.id = r.requester_id
                WHERE r.target_id = ? AND u.locked = FALSE
                """ + cursorClause + " ORDER BY r.id LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        List<IncomingFriendRequest> requests = jdbc.query(sql, (rs, row) -> new IncomingFriendRequest(
                rs.getObject("request_id", UUID.class), mapUser(rs, row)), args.toArray());
        boolean more = requests.size() > limit;
        List<IncomingFriendRequest> items = more ? requests.subList(0, limit) : requests;
        return new FriendRequestPage(List.copyOf(items), more ? items.getLast().id().toString() : null);
    }

    @Override
    public UserPage<SocialUser> friends(UUID userId, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND u.id > ?";
        String sql = """
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender
                FROM chat_v1.friendship f
                JOIN chat_v1.user_account u ON u.id = CASE WHEN f.user_low_id = ? THEN f.user_high_id ELSE f.user_low_id END
                WHERE (f.user_low_id = ? OR f.user_high_id = ?) AND u.locked = FALSE
                """ + cursorClause + " ORDER BY u.id LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(userId, userId, userId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        return page(jdbc.query(sql, USER_MAPPER, args.toArray()), limit);
    }

    @Override
    public OutgoingFriendRequestPage outgoingRequests(UUID userId, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND r.id > ?";
        String sql = """
                SELECT r.id AS request_id, u.id, u.username, u.full_name, u.address, u.birth_date, u.gender
                FROM chat_v1.friend_request r
                JOIN chat_v1.user_account u ON u.id = r.target_id
                WHERE r.requester_id = ? AND u.locked = FALSE
                """ + cursorClause + " ORDER BY r.id LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        List<OutgoingFriendRequest> requests = jdbc.query(sql, (rs, row) -> new OutgoingFriendRequest(
                rs.getObject("request_id", UUID.class), mapUser(rs, row)), args.toArray());
        boolean more = requests.size() > limit;
        List<OutgoingFriendRequest> items = more ? requests.subList(0, limit) : requests;
        return new OutgoingFriendRequestPage(List.copyOf(items),
                more ? items.getLast().id().toString() : null);
    }

    @Override
    public UserPage<SocialUser> blocked(UUID userId, UUID cursor, int limit) {
        String cursorClause = cursor == null ? "" : "AND u.id > ?";
        String sql = """
                SELECT u.id, u.username, u.full_name, u.address, u.birth_date, u.gender
                FROM chat_v1.user_block b
                JOIN chat_v1.user_account u ON u.id = b.blocked_id
                WHERE b.blocker_id = ?
                """ + cursorClause + " ORDER BY u.id LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (cursor != null) args.add(cursor);
        args.add(limit + 1);
        return page(jdbc.query(sql, USER_MAPPER, args.toArray()), limit);
    }

    @Override
    @Transactional
    public void sendRequest(UUID requesterId, UUID targetId) {
        lockUsers(requesterId, targetId);
        if (blockedEitherWay(requesterId, targetId) || areFriends(requesterId, targetId)) {
            throw new SocialConflictException();
        }
        UUID[] pair = pair(requesterId, targetId);
        try {
            jdbc.update("""
                    INSERT INTO chat_v1.friend_request (id, requester_id, target_id, pair_low_id, pair_high_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), requesterId, targetId, pair[0], pair[1]);
        } catch (DuplicateKeyException exception) {
            throw new SocialConflictException();
        }
    }

    @Override
    @Transactional
    public void decideRequest(UUID userId, UUID requestId, boolean accept) {
        List<UUID> pairIds = jdbc.query("SELECT requester_id, target_id FROM chat_v1.friend_request WHERE id = ?",
                (rs, row) -> List.of(rs.getObject("requester_id", UUID.class), rs.getObject("target_id", UUID.class)),
                requestId).stream().findFirst().orElseThrow(UserNotFoundException::new);
        UUID requesterId = pairIds.getFirst();
        UUID targetId = pairIds.getLast();
        if (!targetId.equals(userId)) throw new UserNotFoundException();
        lockUsers(requesterId, targetId);
        List<UUID> pending = jdbc.query("""
                SELECT id FROM chat_v1.friend_request WHERE id = ? AND target_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), requestId, userId);
        if (pending.isEmpty()) throw new UserNotFoundException();
        if (accept) {
            UUID[] pair = pair(requesterId, targetId);
            jdbc.update("INSERT INTO chat_v1.friendship (user_low_id, user_high_id) VALUES (?, ?)", pair[0], pair[1]);
        }
        jdbc.update("DELETE FROM chat_v1.friend_request WHERE id = ?", requestId);
    }

    @Override
    @Transactional
    public void cancelRequest(UUID userId, UUID requestId) {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM chat_v1.friend_request WHERE id = ? AND requester_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), requestId, userId);
        if (ids.isEmpty()) throw new UserNotFoundException();
        jdbc.update("DELETE FROM chat_v1.friend_request WHERE id = ?", requestId);
    }

    @Override
    @Transactional
    public void removeFriend(UUID userId, UUID friendId) {
        lockUsers(userId, friendId);
        UUID[] pair = pair(userId, friendId);
        jdbc.update("DELETE FROM chat_v1.friendship WHERE user_low_id = ? AND user_high_id = ?", pair[0], pair[1]);
    }

    @Override
    @Transactional
    public void block(UUID blockerId, UUID blockedId) {
        lockUsers(blockerId, blockedId);
        jdbc.update("INSERT INTO chat_v1.user_block (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                blockerId, blockedId);
        UUID[] pair = pair(blockerId, blockedId);
        jdbc.update("DELETE FROM chat_v1.friendship WHERE user_low_id = ? AND user_high_id = ?", pair[0], pair[1]);
        jdbc.update("DELETE FROM chat_v1.friend_request WHERE pair_low_id = ? AND pair_high_id = ?", pair[0], pair[1]);
    }

    @Override
    @Transactional
    public void unblock(UUID blockerId, UUID blockedId) {
        lockUsers(blockerId, blockedId);
        jdbc.update("DELETE FROM chat_v1.user_block WHERE blocker_id = ? AND blocked_id = ?", blockerId, blockedId);
    }

    @Override
    public boolean canStartDirectConversation(UUID firstId, UUID secondId) {
        UUID[] pair = pair(firstId, secondId);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.friendship f
                    WHERE f.user_low_id = ? AND f.user_high_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM chat_v1.user_block b
                    WHERE (b.blocker_id = ? AND b.blocked_id = ?)
                       OR (b.blocker_id = ? AND b.blocked_id = ?))
                """, Boolean.class, pair[0], pair[1], firstId, secondId, secondId, firstId));
    }

    @Override
    @Transactional
    public UUID submitReport(UUID reporterId, ReportSubmission submission) {
        UUID reportedId = submission.reportedUserId();
        lockUsers(reporterId, reportedId);
        UUID reportId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO chat_v1.spam_report (id, reporter_id, reported_id, reason)
                    VALUES (?, ?, ?, ?)
                    """, reportId, reporterId, reportedId, submission.reason());
        } catch (DuplicateKeyException exception) {
            throw new SocialConflictException();
        }
        if (submission.selectedEvidence() != null) {
            jdbc.batchUpdate("""
                    INSERT INTO chat_v1.spam_report_evidence (report_id, message_id, submitted_content)
                    VALUES (?, ?, ?)
                    """, submission.selectedEvidence(), submission.selectedEvidence().size(),
                    (statement, evidence) -> {
                        statement.setObject(1, reportId);
                        statement.setObject(2, evidence.messageId());
                        statement.setString(3, evidence.submittedContent());
                    });
        }
        return reportId;
    }

    private void lockUsers(UUID firstId, UUID secondId) {
        if (firstId.equals(secondId)) throw new SocialConflictException();
        List<UUID> locked = jdbc.queryForList("""
                SELECT id FROM chat_v1.user_account
                WHERE id IN (?, ?) AND locked = FALSE
                ORDER BY id FOR UPDATE
                """, UUID.class, firstId, secondId);
        if (locked.size() != 2) throw new UserNotFoundException();
    }

    private boolean blockedEitherWay(UUID firstId, UUID secondId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.user_block
                    WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, firstId, secondId, secondId, firstId));
    }

    private boolean areFriends(UUID firstId, UUID secondId) {
        UUID[] pair = pair(firstId, secondId);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM chat_v1.friendship WHERE user_low_id = ? AND user_high_id = ?)
                """, Boolean.class, pair[0], pair[1]));
    }

    private static UUID[] pair(UUID firstId, UUID secondId) {
        return firstId.toString().compareTo(secondId.toString()) < 0
                ? new UUID[]{firstId, secondId} : new UUID[]{secondId, firstId};
    }

    private static UserPage<SocialUser> page(List<SocialUser> users, int limit) {
        boolean more = users.size() > limit;
        List<SocialUser> items = more ? users.subList(0, limit) : users;
        return new UserPage<>(List.copyOf(items), more ? items.getLast().id().toString() : null);
    }

    private static SocialUser mapUser(ResultSet rs, int row) throws SQLException {
        return new SocialUser(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("full_name"),
                rs.getString("address"), rs.getObject("birth_date", LocalDate.class), rs.getString("gender"), "offline");
    }
}
