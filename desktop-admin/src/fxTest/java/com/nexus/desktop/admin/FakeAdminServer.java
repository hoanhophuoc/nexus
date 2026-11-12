package com.nexus.desktop.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal loopback API implementing every endpoint the admin screens use. */
final class FakeAdminServer implements AutoCloseable {
    static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID REPORTER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID REPORTED_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID REPORT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final UUID GROUP_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    static final UUID AUDIT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    static final String GROUP_NAME = "Moderators";
    static final String USERNAME = "alice";
    static final String FULL_NAME = "Alice Example";
    static final String EMAIL = "alice@example.test";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> lockedUsers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UserRecord> users = new LinkedHashMap<>();
    private final Set<UUID> resetRequests = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deletedUsers = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> reportDecision = new AtomicReference<>();
    private final AtomicReference<String> reportStatus = new AtomicReference<>("pending");
    private final AtomicInteger logoutCount = new AtomicInteger();
    private volatile String loginUsername;
    private volatile String loginPassword;
    private volatile UUID createdUserId;
    private volatile String createdUsername;
    private volatile String updatedFullName;

    FakeAdminServer() throws IOException {
        users.put(USER_ID, new UserRecord(USER_ID, USERNAME, FULL_NAME, EMAIL,
                "12 Example Street", LocalDate.of(1990, 4, 2), "female"));
        users.put(REPORTER_ID, new UserRecord(REPORTER_ID, "bob", "Bob Reporter", "bob@example.test",
                "2 Report Lane", LocalDate.of(1992, 6, 1), "male"));
        users.put(REPORTED_ID, new UserRecord(REPORTED_ID, "mallory", "Mallory Reported",
                "mallory@example.test", "3 Report Lane", LocalDate.of(1993, 8, 9), "female"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/v1/auth", this::auth);
        server.createContext("/api/v1/admin/users/", this::userAction);
        server.createContext("/api/v1/admin/users", this::users);
        server.createContext("/api/v1/admin/reports/", this::decideReport);
        server.createContext("/api/v1/admin/reports", this::reports);
        server.createContext("/api/v1/admin/groups/", this::members);
        server.createContext("/api/v1/admin/groups", this::groups);
        server.createContext("/api/v1/admin/registrations", this::registrations);
        server.createContext("/api/v1/admin/analytics/registrations/monthly", this::monthlyRegistrations);
        server.createContext("/api/v1/admin/analytics/app-opens/monthly", this::monthlyAppOpens);
        server.createContext("/api/v1/admin/analytics/activity", this::activity);
        server.createContext("/api/v1/admin/analytics/friends", this::friends);
        server.createContext("/api/v1/admin/audit-events", this::audit);
        server.start();
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    String loginUsername() { return loginUsername; }
    String loginPassword() { return loginPassword; }
    Set<UUID> lockedUsers() { return Set.copyOf(lockedUsers); }
    String reportDecision() { return reportDecision.get(); }
    int logoutCount() { return logoutCount.get(); }
    UUID createdUserId() { return createdUserId; }
    String createdUsername() { return createdUsername; }
    String updatedFullName() { return updatedFullName; }
    boolean resetRequested(UUID userId) { return resetRequests.contains(userId); }
    boolean deleted(UUID userId) { return deletedUsers.contains(userId); }
    synchronized boolean hasUser(UUID userId) { return users.containsKey(userId); }

    private void auth(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/csrf")) {
            respondJson(exchange, 200, "{\"token\":\"fake-csrf-token\"}");
        } else if (path.endsWith("/login")) {
            String body = readText(exchange);
            loginUsername = jsonText(body, "username");
            loginPassword = jsonText(body, "password");
            respondJson(exchange, 200, """
                    {"id":"%s","username":"admin","fullName":"Alice Admin",
                     "email":"admin@example.test","presence":"online"}
                    """.formatted(ADMIN_ID));
        } else if (path.endsWith("/logout")) {
            logoutCount.incrementAndGet();
            respondNoContent(exchange);
        } else {
            respondJson(exchange, 404, "{}");
        }
    }

    private void users(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            String body = readText(exchange);
            UUID id = UUID.randomUUID();
            UserRecord record = new UserRecord(id,
                    requiredText(body, "username"), requiredText(body, "fullName"),
                    requiredText(body, "email"), requiredText(body, "address"),
                    LocalDate.parse(requiredText(body, "birthDate")), requiredText(body, "gender"));
            synchronized (this) {
                users.put(id, record);
            }
            createdUserId = id;
            createdUsername = record.username;
            respondJson(exchange, 200, adminUserJson(record));
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String filter = query == null ? null : queryParameter(query, "query");
        List<UserRecord> matches;
        synchronized (this) {
            matches = users.values().stream()
                    .filter(record -> filter == null || filter.isBlank()
                            || record.username.contains(filter) || record.fullName.contains(filter)
                            || record.email.contains(filter))
                    .toList();
        }
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) body.append(',');
            body.append(adminUserJson(matches.get(i)));
        }
        body.append("]}");
        respondJson(exchange, 200, body.toString());
    }

    private void userAction(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rest = path.substring("/api/v1/admin/users/".length());
        String[] parts = rest.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            respondJson(exchange, 404, "{}");
            return;
        }
        UUID userId;
        try {
            userId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException invalid) {
            respondJson(exchange, 400, "{}");
            return;
        }
        if (parts.length == 1) {
            if ("PATCH".equals(exchange.getRequestMethod())) {
                update(exchange, userId);
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                delete(exchange, userId);
            } else {
                respondJson(exchange, 405, "{}");
            }
            return;
        }
        switch (parts[1]) {
            case "lock" -> lock(exchange, userId);
            case "login-history" -> loginHistory(exchange, userId);
            case "friends" -> userFriends(exchange, userId);
            case "password-reset" -> resetPassword(exchange, userId);
            default -> respondJson(exchange, 404, "{}");
        }
    }

    private void update(HttpExchange exchange, UUID userId) throws IOException {
        String body = readText(exchange);
        synchronized (this) {
            UserRecord existing = users.get(userId);
            if (existing == null) {
                respondJson(exchange, 404, "{}");
                return;
            }
            UserRecord updated = new UserRecord(userId, existing.username,
                    textOr(body, "fullName", existing.fullName), textOr(body, "email", existing.email),
                    textOr(body, "address", existing.address),
                    body.contains("\"birthDate\"") ? LocalDate.parse(requiredText(body, "birthDate"))
                            : existing.birthDate,
                    textOr(body, "gender", existing.gender));
            users.put(userId, updated);
            updatedFullName = updated.fullName;
        }
        respondNoContent(exchange);
    }

    private void delete(HttpExchange exchange, UUID userId) throws IOException {
        if (!"DELETE".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        synchronized (this) {
            if (users.remove(userId) == null) {
                respondJson(exchange, 404, "{}");
                return;
            }
        }
        deletedUsers.add(userId);
        lockedUsers.remove(userId);
        respondNoContent(exchange);
    }

    private void lock(HttpExchange exchange, UUID userId) throws IOException {
        if (!"PUT".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        String body = readText(exchange);
        if (body.contains("\"locked\":true")) lockedUsers.add(userId);
        else lockedUsers.remove(userId);
        respondNoContent(exchange);
    }

    private void loginHistory(HttpExchange exchange, UUID userId) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        String username;
        String fullName;
        synchronized (this) {
            UserRecord record = users.get(userId);
            username = record == null ? "unknown" : record.username;
            fullName = record == null ? "Unknown" : record.fullName;
        }
        respondJson(exchange, 200, """
                {"items":[
                  {"userId":"%s","username":"%s","fullName":"%s","occurredAt":"2026-11-07T09:15:00Z"},
                  {"userId":"%s","username":"%s","fullName":"%s","occurredAt":"2026-11-06T18:45:00Z"}
                ]}
                """.formatted(userId, username, fullName, userId, username, fullName));
    }

    private void userFriends(HttpExchange exchange, UUID userId) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        UserRecord friend;
        synchronized (this) {
            friend = users.values().stream()
                    .filter(record -> !record.id.equals(userId))
                    .findFirst().orElse(null);
        }
        String friendJson = friend == null ? "" : """
                {"id":"%s","username":"%s","fullName":"%s","address":"%s",
                 "birthDate":"%s","gender":"%s","presence":"online"}
                """.formatted(friend.id, friend.username, friend.fullName, friend.address,
                friend.birthDate, friend.gender);
        respondJson(exchange, 200, "{\"items\":[" + friendJson + "]}");
    }

    private void resetPassword(HttpExchange exchange, UUID userId) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        resetRequests.add(userId);
        respondNoContent(exchange);
    }

    private void reports(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        respondJson(exchange, 200, "{\"items\":[%s]}".formatted("""
                {"id":"%s",
                 "reporter":{"id":"%s","username":"bob","fullName":"Bob Reporter","presence":"online"},
                 "reportedUser":{"id":"%s","username":"mallory","fullName":"Mallory Reported","presence":"offline"},
                 "reason":"Harassment in a direct conversation",
                 "createdAt":"2026-11-07T10:00:00Z","status":"%s",
                 "selectedEvidence":[{"messageId":"77777777-7777-4777-8777-777777777777",
                    "submittedContent":"I will find you after class."}]}
                """.formatted(REPORT_ID, REPORTER_ID, REPORTED_ID, reportStatus.get())));
    }

    private void decideReport(HttpExchange exchange) throws IOException {
        if (!"PUT".equals(exchange.getRequestMethod()) || !exchange.getRequestURI().getPath().endsWith("/decision")) {
            respondJson(exchange, 404, "{}");
            return;
        }
        String body = readText(exchange);
        boolean uphold = body.contains("\"decision\":\"uphold\"");
        reportDecision.set(uphold ? "uphold" : "dismiss");
        reportStatus.set(uphold ? "upheld" : "dismissed");
        if (uphold && body.contains("\"lockReportedUser\":true")) lockedUsers.add(REPORTED_ID);
        respondNoContent(exchange);
    }

    private void groups(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, """
                {"items":[{"id":"%s","name":"%s","type":"group","createdAt":"2026-11-07T10:00:00Z"}]}
                """.formatted(GROUP_ID, GROUP_NAME));
    }

    private void members(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().endsWith("/members")) {
            respondJson(exchange, 404, "{}");
            return;
        }
        respondJson(exchange, 200, """
                {"items":[
                  {"user":{"id":"%s","username":"admin","fullName":"Alice Admin","presence":"online"},
                   "role":"admin","joinedEpoch":0},
                  {"user":{"id":"%s","username":"bob","fullName":"Bob Reporter","presence":"online"},
                   "role":"member","joinedEpoch":1}
                ]}
                """.formatted(ADMIN_ID, REPORTER_ID));
    }

    private void registrations(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, "{\"items\":[" + adminUserJson(users.get(USER_ID)) + "]}");
    }

    private void monthlyRegistrations(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, """
                {"year":2026,"months":[{"month":1,"count":3},{"month":2,"count":5},{"month":3,"count":2}]}
                """);
    }

    private void monthlyAppOpens(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, """
                {"year":2026,"months":[{"month":1,"count":9},{"month":2,"count":12},{"month":3,"count":7}]}
                """);
    }

    private void activity(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, """
                {"items":[{"user":%s,"appOpens":9,"peopleChattedWith":2,"groupsChattedWith":1}]}
                """.formatted(adminUserJson(users.get(USER_ID))));
    }

    private void friends(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, """
                {"items":[{"user":%s,"directFriends":2,"friendsOfFriends":4}]}
                """.formatted(adminUserJson(users.get(USER_ID))));
    }

    private void audit(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, """
                {"items":[{"id":"%s","actorId":"%s","action":"admin.user.lock",
                 "targetId":"%s","occurredAt":"2026-11-07T10:00:00Z"}]}
                """.formatted(AUDIT_ID, ADMIN_ID, USER_ID));
    }

    private String adminUserJson(UserRecord record) {
        if (record == null) return "{}";
        return """
                {"id":"%s","username":"%s","fullName":"%s","email":"%s","address":"%s",
                 "birthDate":"%s","gender":"%s","status":"%s",
                 "createdAt":"2026-11-07T10:00:00Z"}
                """.formatted(record.id, record.username, record.fullName, record.email, record.address,
                record.birthDate, record.gender,
                lockedUsers.contains(record.id) ? "locked" : "active");
    }

    private static String textOr(String json, String name, String fallback) {
        String value = jsonText(json, name);
        return value == null ? fallback : value;
    }

    private static String requiredText(String json, String name) {
        String value = jsonText(json, name);
        if (value == null) throw new IllegalArgumentException("Missing JSON field: " + name);
        return value;
    }

    private static String jsonText(String json, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String queryParameter(String query, String name) {
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && name.equals(pair.substring(0, separator))) {
                return java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String readText(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }

    private static void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record UserRecord(UUID id, String username, String fullName, String email,
                              String address, LocalDate birthDate, String gender) {
    }
}
