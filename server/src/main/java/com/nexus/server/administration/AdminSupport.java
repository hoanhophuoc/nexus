package com.nexus.server.administration;

import com.nexus.server.LikePatterns;
import com.nexus.server.identity.SessionIdentity;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.IntStream;

final class AdminSupport {

    private AdminSupport() {
    }

    static void requireAdmin(SessionIdentity actor) {
        if (actor == null || !"admin".equals(actor.role())) {
            throw new AdminForbiddenException();
        }
    }

    static void requireRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("The date range must have from before to.");
        }
    }

    static String comparisonOperator(String comparison) {
        return switch (comparison == null ? "eq" : comparison) {
            case "eq" -> "=";
            case "lt" -> "<";
            case "gt" -> ">";
            default -> throw new IllegalArgumentException("Unsupported comparison.");
        };
    }

    static String orderDirection(String direction) {
        return switch (direction == null ? "asc" : direction) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new IllegalArgumentException("Unsupported sort direction.");
        };
    }

    /**
     * Filters accounts by a substring of the username, full name or email. The predicates use the
     * columns the trigram indexes from V9 and V10 cover, so a term that matches nothing reads the
     * index instead of walking every account.
     */
    static void appendUserFilter(StringBuilder where, List<Object> args, String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        String pattern = LikePatterns.containing(query);
        where.append("""
                 AND (u.username_normalized LIKE ? ESCAPE '\\'
                   OR lower(u.full_name) LIKE ? ESCAPE '\\'
                   OR u.email_normalized LIKE ? ESCAPE '\\')
                """);
        args.add(pattern);
        args.add(pattern);
        args.add(pattern);
    }

    private static final String GROUP_CREATED_AT_ASC = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
            ORDER BY c.created_at ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_ASC_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND (c.created_at, c.id) > (?, ?)
            ORDER BY c.created_at ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_ASC_FILTERED = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
            ORDER BY c.created_at ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_ASC_FILTERED_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
              AND (c.created_at, c.id) > (?, ?)
            ORDER BY c.created_at ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_DESC = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_DESC_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND (c.created_at, c.id) < (?, ?)
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_DESC_FILTERED = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_CREATED_AT_DESC_FILTERED_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
              AND (c.created_at, c.id) < (?, ?)
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_NAME_ASC = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
            ORDER BY lower(c.name) ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_NAME_ASC_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND (lower(c.name), c.id) > (?, ?)
            ORDER BY lower(c.name) ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_NAME_ASC_FILTERED = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
            ORDER BY lower(c.name) ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_NAME_ASC_FILTERED_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
              AND (lower(c.name), c.id) > (?, ?)
            ORDER BY lower(c.name) ASC, c.id ASC
            LIMIT ?
            """;

    private static final String GROUP_NAME_DESC = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
            ORDER BY lower(c.name) DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_NAME_DESC_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND (lower(c.name), c.id) < (?, ?)
            ORDER BY lower(c.name) DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_NAME_DESC_FILTERED = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
            ORDER BY lower(c.name) DESC, c.id DESC
            LIMIT ?
            """;

    private static final String GROUP_NAME_DESC_FILTERED_CURSOR = """
            SELECT c.id, c.name, c.created_at FROM nexus.conversation c
            WHERE c.type = 'group'
              AND lower(c.name) LIKE ? ESCAPE '\\'
              AND (lower(c.name), c.id) < (?, ?)
            ORDER BY lower(c.name) DESC, c.id DESC
            LIMIT ?
            """;

    /**
     * The admin group list is four independent choices (sort column, direction, whether a search
     * term narrows it, whether a cursor continues it). Every combination is written out as a
     * constant statement instead of being concatenated per request, so the search predicate stays a
     * plain LIKE the planner can match against conversation_group_name_trgm_idx.
     */
    static String groupPageSql(String sortField, String orderDirection, boolean filtered,
                               boolean cursor) {
        return switch (sortField + "-" + orderDirection + (filtered ? "-filtered" : "")
                + (cursor ? "-cursor" : "")) {
            case "createdAt-ASC" -> GROUP_CREATED_AT_ASC;
            case "createdAt-ASC-filtered" -> GROUP_CREATED_AT_ASC_FILTERED;
            case "createdAt-ASC-cursor" -> GROUP_CREATED_AT_ASC_CURSOR;
            case "createdAt-ASC-filtered-cursor" -> GROUP_CREATED_AT_ASC_FILTERED_CURSOR;
            case "createdAt-DESC" -> GROUP_CREATED_AT_DESC;
            case "createdAt-DESC-filtered" -> GROUP_CREATED_AT_DESC_FILTERED;
            case "createdAt-DESC-cursor" -> GROUP_CREATED_AT_DESC_CURSOR;
            case "createdAt-DESC-filtered-cursor" -> GROUP_CREATED_AT_DESC_FILTERED_CURSOR;
            case "name-ASC" -> GROUP_NAME_ASC;
            case "name-ASC-filtered" -> GROUP_NAME_ASC_FILTERED;
            case "name-ASC-cursor" -> GROUP_NAME_ASC_CURSOR;
            case "name-ASC-filtered-cursor" -> GROUP_NAME_ASC_FILTERED_CURSOR;
            case "name-DESC" -> GROUP_NAME_DESC;
            case "name-DESC-filtered" -> GROUP_NAME_DESC_FILTERED;
            case "name-DESC-cursor" -> GROUP_NAME_DESC_CURSOR;
            case "name-DESC-filtered-cursor" -> GROUP_NAME_DESC_FILTERED_CURSOR;
            default -> throw new IllegalArgumentException("Unsupported group page.");
        };
    }

    static String encodeCursor(UUID id, String sortKey) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((id + "\0" + sortKey).getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String encoded) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                    .split("\0", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(UUID.fromString(parts[0]), parts[1]);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid cursor.", exception);
        }
    }

    static Instant cursorInstant(String sortKey) {
        try {
            return Instant.parse(sortKey);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid cursor.", exception);
        }
    }

    static String cursorKey(AdminUserView user, String sortField) {
        return "createdAt".equals(sortField) ? user.createdAt().toString()
                : user.fullName().toLowerCase(Locale.ROOT);
    }

    static AdminUserView mapUser(ResultSet rs, int row) throws SQLException {
        return new AdminUserView(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("full_name"), rs.getString("email"), rs.getString("address"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("gender"),
                rs.getBoolean("locked") ? "locked" : "active",
                rs.getTimestamp("created_at").toInstant());
    }

    static MonthlyCounts monthlyCounts(int year, Map<Integer, Integer> counts) {
        List<MonthlyCount> months = IntStream.rangeClosed(1, 12)
                .mapToObj(month -> new MonthlyCount(month, counts.getOrDefault(month, 0)))
                .toList();
        return new MonthlyCounts(year, months);
    }

    record Cursor(UUID id, String sortKey) {
    }
}
