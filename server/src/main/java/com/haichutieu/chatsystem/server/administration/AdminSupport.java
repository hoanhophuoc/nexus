package com.haichutieu.chatsystem.server.administration;

import com.haichutieu.chatsystem.server.identity.SessionIdentity;

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

    static void appendUserFilter(StringBuilder where, List<Object> args, String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        String term = query.strip();
        where.append("""
                 AND (position(lower(?) in lower(u.username)) > 0
                   OR position(lower(?) in lower(u.full_name)) > 0
                   OR position(lower(?) in lower(u.email)) > 0)
                """);
        args.add(term);
        args.add(term);
        args.add(term);
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
