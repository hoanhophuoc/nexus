package com.haichutieu.chatsystem.server.administration;

import com.haichutieu.chatsystem.server.identity.SessionIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminAnalyticsService {
    private static final String USER_COLUMNS =
            "u.id, u.username, u.full_name, u.email, u.address, u.birth_date, u.gender, u.locked, u.created_at";

    private final JdbcTemplate jdbc;

    public AdminAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FriendAnalyticsPage friends(SessionIdentity actor, String query, Integer directFriendCount,
                                       String comparison, String sort, String direction, String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        validateCountFilter(directFriendCount, comparison, "directFriendCount");
        String sortField = sort == null ? "name" : sort;
        String order = switch (sortField) {
            case "name" -> "lower(u.full_name)";
            case "createdAt" -> "u.created_at";
            default -> throw new IllegalArgumentException("Unsupported analytics sort field.");
        };
        String orderDirection = AdminSupport.orderDirection(direction);
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE TRUE");
        AdminSupport.appendUserFilter(where, args, query);
        if (directFriendCount != null) {
            where.append(" AND COALESCE(d.direct_count, 0) ")
                    .append(AdminSupport.comparisonOperator(comparison)).append(" ?");
            args.add(directFriendCount);
        }
        appendCursor(where, args, order, orderDirection, cursor, sortField);
        args.add(pageSize + 1);
        String sql = """
                WITH friends AS (
                    SELECT f.user_low_id AS user_id, f.user_high_id AS friend_id FROM chat_v1.friendship f
                    UNION ALL
                    SELECT f.user_high_id, f.user_low_id FROM chat_v1.friendship f
                ), direct AS (
                    SELECT user_id, count(*) AS direct_count FROM friends GROUP BY user_id
                ), second_degree AS (
                    SELECT f.user_id,
                           CASE WHEN f2.user_low_id = f.friend_id THEN f2.user_high_id ELSE f2.user_low_id END AS other_id
                    FROM friends f
                    JOIN chat_v1.friendship f2 ON f2.user_low_id = f.friend_id OR f2.user_high_id = f.friend_id
                ), friends_of_friends AS (
                    SELECT s.user_id, count(DISTINCT s.other_id) AS fof_count
                    FROM second_degree s
                    WHERE s.other_id <> s.user_id
                      AND NOT EXISTS (SELECT 1 FROM chat_v1.friendship d
                                      WHERE d.user_low_id = LEAST(s.user_id, s.other_id)
                                        AND d.user_high_id = GREATEST(s.user_id, s.other_id))
                    GROUP BY s.user_id
                )
                SELECT %s, COALESCE(d.direct_count, 0) AS direct_count,
                       COALESCE(fof.fof_count, 0) AS fof_count
                FROM chat_v1.user_account u
                LEFT JOIN direct d ON d.user_id = u.id
                LEFT JOIN friends_of_friends fof ON fof.user_id = u.id
                """.formatted(USER_COLUMNS) + where + " ORDER BY " + order + " " + orderDirection
                + ", u.id " + orderDirection + " LIMIT ?";
        List<FriendAnalytics> found = jdbc.query(sql, (rs, row) -> new FriendAnalytics(
                AdminSupport.mapUser(rs, row), rs.getInt("direct_count"), rs.getInt("fof_count")),
                args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<FriendAnalytics> items = List.copyOf(found.subList(0, Math.min(found.size(), pageSize)));
        String next = hasMore ? AdminSupport.encodeCursor(items.getLast().user().id(),
                AdminSupport.cursorKey(items.getLast().user(), sortField)) : null;
        return new FriendAnalyticsPage(items, next);
    }

    public ActivityAnalyticsPage activity(SessionIdentity actor, Instant from, Instant to, String query,
                                          Integer activityCount, String comparison, String sort, String direction,
                                          String cursor, int limit) {
        AdminSupport.requireAdmin(actor);
        AdminSupport.requireRange(from, to);
        validateCountFilter(activityCount, comparison, "activityCount");
        String sortField = sort == null ? "name" : sort;
        String order = switch (sortField) {
            case "name" -> "lower(u.full_name)";
            case "createdAt" -> "u.created_at";
            default -> throw new IllegalArgumentException("Unsupported analytics sort field.");
        };
        String orderDirection = AdminSupport.orderDirection(direction);
        int pageSize = Math.clamp(limit, 1, 100);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        StringBuilder where = new StringBuilder("WHERE TRUE");
        AdminSupport.appendUserFilter(where, args, query);
        if (activityCount != null) {
            where.append(" AND (COALESCE(o.opens, 0) + COALESCE(dp.peers, 0) + COALESCE(gc.groups, 0)) ")
                    .append(AdminSupport.comparisonOperator(comparison)).append(" ?");
            args.add(activityCount);
        }
        appendCursor(where, args, order, orderDirection, cursor, sortField);
        args.add(pageSize + 1);
        String sql = """
                WITH opens AS (
                    SELECT user_id, count(*) AS opens FROM chat_v1.login_history
                    WHERE occurred_at >= ? AND occurred_at < ? GROUP BY user_id
                ), direct_peers AS (
                    SELECT cm.user_id, count(DISTINCT peer.user_id) AS peers
                    FROM chat_v1.conversation_member cm
                    JOIN chat_v1.conversation c ON c.id = cm.conversation_id AND c.type = 'direct'
                    JOIN chat_v1.conversation_member peer
                      ON peer.conversation_id = c.id AND peer.user_id <> cm.user_id
                    WHERE EXISTS (SELECT 1 FROM chat_v1.message m WHERE m.conversation_id = c.id
                                  AND m.created_at >= ? AND m.created_at < ?)
                    GROUP BY cm.user_id
                ), group_chats AS (
                    SELECT cm.user_id, count(DISTINCT cm.conversation_id) AS groups
                    FROM chat_v1.conversation_member cm
                    JOIN chat_v1.conversation c ON c.id = cm.conversation_id AND c.type = 'group'
                    WHERE EXISTS (SELECT 1 FROM chat_v1.mls_message m WHERE m.conversation_id = c.id
                                  AND m.kind = 'application'
                                  AND m.created_at >= ? AND m.created_at < ?)
                    GROUP BY cm.user_id
                )
                SELECT %s, COALESCE(o.opens, 0) AS app_opens,
                       COALESCE(dp.peers, 0) AS people_chatted_with,
                       COALESCE(gc.groups, 0) AS groups_chatted_with
                FROM chat_v1.user_account u
                LEFT JOIN opens o ON o.user_id = u.id
                LEFT JOIN direct_peers dp ON dp.user_id = u.id
                LEFT JOIN group_chats gc ON gc.user_id = u.id
                """.formatted(USER_COLUMNS) + where + " ORDER BY " + order + " " + orderDirection
                + ", u.id " + orderDirection + " LIMIT ?";
        List<ActivityAnalytics> found = jdbc.query(sql, (rs, row) -> new ActivityAnalytics(
                AdminSupport.mapUser(rs, row), rs.getInt("app_opens"), rs.getInt("people_chatted_with"),
                rs.getInt("groups_chatted_with")), args.toArray());
        boolean hasMore = found.size() > pageSize;
        List<ActivityAnalytics> items = List.copyOf(found.subList(0, Math.min(found.size(), pageSize)));
        String next = hasMore ? AdminSupport.encodeCursor(items.getLast().user().id(),
                AdminSupport.cursorKey(items.getLast().user(), sortField)) : null;
        return new ActivityAnalyticsPage(items, next);
    }

    public MonthlyCounts monthlyRegistrations(SessionIdentity actor, int year) {
        AdminSupport.requireAdmin(actor);
        Map<Integer, Integer> counts = new HashMap<>();
        jdbc.query("""
                SELECT EXTRACT(MONTH FROM created_at AT TIME ZONE 'UTC')::int AS month, count(*) AS total
                FROM chat_v1.user_account
                WHERE created_at >= ? AND created_at < ?
                GROUP BY 1
                """, (RowCallbackHandler) rs -> counts.put(rs.getInt("month"), rs.getInt("total")),
                Timestamp.from(startOfYear(year)), Timestamp.from(startOfYear(year + 1)));
        return AdminSupport.monthlyCounts(year, counts);
    }

    public MonthlyCounts monthlyAppOpens(SessionIdentity actor, int year) {
        AdminSupport.requireAdmin(actor);
        Map<Integer, Integer> counts = new HashMap<>();
        // shortcut: every login records one app open; upgrade if the desktop app
        // starts reusing persisted sessions without authenticating again.
        jdbc.query("""
                SELECT EXTRACT(MONTH FROM occurred_at AT TIME ZONE 'UTC')::int AS month,
                       count(DISTINCT user_id) AS total
                FROM chat_v1.login_history
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY 1
                """, (RowCallbackHandler) rs -> counts.put(rs.getInt("month"), rs.getInt("total")),
                Timestamp.from(startOfYear(year)), Timestamp.from(startOfYear(year + 1)));
        return AdminSupport.monthlyCounts(year, counts);
    }

    private static void validateCountFilter(Integer count, String comparison, String name) {
        if (count != null && count < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
        if (count == null && comparison != null) {
            throw new IllegalArgumentException("A comparison requires " + name + ".");
        }
    }

    private static void appendCursor(StringBuilder where, List<Object> args, String order, String orderDirection,
                                     String cursor, String sortField) {
        if (cursor == null) {
            return;
        }
        AdminSupport.Cursor decoded = AdminSupport.decodeCursor(cursor);
        where.append(" AND (").append(order).append(", u.id) ")
                .append("ASC".equals(orderDirection) ? ">" : "<").append(" (?, ?)");
        args.add("createdAt".equals(sortField) ? Timestamp.from(AdminSupport.cursorInstant(decoded.sortKey()))
                : decoded.sortKey());
        args.add(decoded.id());
    }

    private static Instant startOfYear(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
