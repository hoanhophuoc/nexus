package com.nexus.desktop.user;

import com.nexus.desktop.shared.mls.MlsException;
import com.nexus.desktop.shared.mls.MlsIdentity;
import com.nexus.desktop.shared.mls.MlsKeyPackage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal loopback API implementing the endpoints used by the desktop user flows. */
final class FakeChatServer implements AutoCloseable {
    private static final String DEFAULT_PASSWORD = "correct horse battery staple";
    static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID FRIEND_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID CONVERSATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID GROUP_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    static final String GROUP_NAME = "Team";
    static final String FRIEND_NAME = "Bob";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final byte[] friendKeyPackage;
    private final List<String> sentContents = new CopyOnWriteArrayList<>();
    private final List<String> directMessages = new CopyOnWriteArrayList<>();
    private final List<MlsWireMessage> groupMessages = new CopyOnWriteArrayList<>();
    private final Map<UUID, UUID> incomingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> outgoingRequests = new ConcurrentHashMap<>();
    private final Map<String, FakeAccount> accounts = new ConcurrentHashMap<>();
    private final Set<UUID> blocked = ConcurrentHashMap.newKeySet();
    private final List<String> deletedScopes = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> memberRoles = new ConcurrentHashMap<>();
    private final Set<UUID> removedMembers = ConcurrentHashMap.newKeySet();
    private volatile String loginUsername;
    private volatile String loginPassword;
    private volatile String signedInPassword = DEFAULT_PASSWORD;
    private volatile boolean groupCreated;
    private volatile boolean directVisible = true;
    private volatile byte[] firstCommit;
    private volatile int reports;
    private volatile String reportReason;
    private volatile int reportsWithEvidence;
    private volatile int passwordChanges;
    private volatile int passwordResetRequests;
    private volatile int clearedHistories;
    private volatile int friendRemovals;
    private volatile int blockedRequestAttempts;
    private volatile String profileFullName;
    private volatile String groupName = GROUP_NAME;

    FakeChatServer() throws IOException {
        this(defaultFriendKeyPackage());
    }

    FakeChatServer(byte[] friendKeyPackage) throws IOException {
        this.friendKeyPackage = friendKeyPackage == null ? new byte[0] : friendKeyPackage.clone();
        directMessages.add(messageJson(UUID.fromString("44444444-4444-4444-8444-444444444444"),
                FRIEND_ID, "hello from history"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/v1/auth", this::auth);
        server.createContext("/api/v1/conversations/", this::conversationMessages);
        server.createContext("/api/v1/conversations", this::conversations);
        server.createContext("/api/v1/friends", this::friends);
        server.createContext("/api/v1/friend-requests", this::friendRequests);
        server.createContext("/api/v1/blocks", this::blocks);
        server.createContext("/api/v1/reports", this::reports);
        server.createContext("/api/v1/users", this::usersSearch);
        server.createContext("/api/v1/users/", this::users);
        server.createContext("/api/v1/groups/", this::groups);
        server.createContext("/api/v1/groups", this::createGroup);
        server.start();
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    String loginUsername() {
        return loginUsername;
    }

    String loginPassword() {
        return loginPassword;
    }

    List<String> sentContents() {
        return sentContents;
    }

    int reports() { return reports; }
    String reportReason() { return reportReason; }
    int reportsWithEvidence() { return reportsWithEvidence; }
    int passwordChanges() { return passwordChanges; }
    int passwordResetRequests() { return passwordResetRequests; }
    int clearedHistories() { return clearedHistories; }
    int friendRemovals() { return friendRemovals; }
    List<String> deletedScopes() { return deletedScopes; }
    Set<UUID> blockedUsers() { return Set.copyOf(blocked); }
    String profileFullName() { return profileFullName; }
    String groupName() { return groupName; }
    Map<UUID, String> memberRoles() { return Map.copyOf(memberRoles); }
    Set<UUID> removedMembers() { return Set.copyOf(removedMembers); }
    int incomingRequestCount() { return incomingRequests.size(); }
    int outgoingRequestCount() { return outgoingRequests.size(); }
    int registrationCount() { return accounts.size(); }
    int blockedRequestAttempts() { return blockedRequestAttempts; }

    UUID addIncomingRequest(UUID from) {
        UUID id = UUID.randomUUID();
        incomingRequests.put(id, from);
        return id;
    }

    List<byte[]> groupCiphertexts() {
        return groupMessages.stream()
                .filter(message -> "application".equals(message.kind()))
                .map(MlsWireMessage::ciphertext)
                .toList();
    }

    byte[] firstCommit() {
        return firstCommit == null ? null : firstCommit.clone();
    }

    UUID addGroupApplication(byte[] ciphertext, long epoch) {
        UUID id = UUID.randomUUID();
        groupMessages.add(new MlsWireMessage(id, epoch, "application", ciphertext.clone()));
        return id;
    }

    String addDirectMessage(String content) {
        String message = messageJson(UUID.randomUUID(), FRIEND_ID, content);
        directMessages.add(message);
        return message;
    }

    void hideDirectConversation() {
        directVisible = false;
    }

    private void auth(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/csrf")) {
            respondJson(exchange, 200, "{\"token\":\"fake-csrf-token\"}");
        } else if (path.endsWith("/login")) {
            String body = readText(exchange);
            loginUsername = jsonText(body, "username");
            loginPassword = jsonText(body, "password");
            FakeAccount account = loginUsername == null ? null
                    : accounts.get(loginUsername.strip().toLowerCase(Locale.ROOT));
            if (account != null) {
                if (!account.password().equals(loginPassword)) {
                    respondJson(exchange, 401, "{}");
                    return;
                }
                signedInPassword = loginPassword;
                respondJson(exchange, 200, account.profileJson());
                return;
            }
            if (!"alice".equalsIgnoreCase(loginUsername) || !DEFAULT_PASSWORD.equals(loginPassword)) {
                respondJson(exchange, 401, "{}");
                return;
            }
            signedInPassword = loginPassword;
            respondJson(exchange, 200, """
                    {"id":"%s","username":"alice","fullName":"Alice Example",
                     "email":"alice@example.test","presence":"online"}
                    """.formatted(USER_ID));
        } else if (path.endsWith("/logout")) {
            respondJson(exchange, 204, "");
        } else if (path.endsWith("/register")) {
            String body = readText(exchange);
            String username = jsonText(body, "username");
            String email = jsonText(body, "email");
            String password = jsonText(body, "password");
            if (username == null || username.isBlank() || email == null || email.isBlank()
                    || password == null || password.isBlank()) {
                respondJson(exchange, 400, "{}");
                return;
            }
            String key = username.strip().toLowerCase(Locale.ROOT);
            boolean duplicate = accounts.containsKey(key) || "alice".equals(key)
                    || accounts.values().stream().anyMatch(existing ->
                    existing.email().equalsIgnoreCase(email));
            if (duplicate) {
                respondJson(exchange, 409, "{}");
                return;
            }
            FakeAccount account = new FakeAccount(UUID.randomUUID(), username.strip(),
                    jsonText(body, "fullName"), email, jsonText(body, "address"),
                    jsonText(body, "birthDate"), jsonText(body, "gender"), password);
            accounts.put(key, account);
            respondJson(exchange, 201, account.profileJson());
        } else if (path.endsWith("/password-reset/confirm")) {
            readText(exchange);
            respondJson(exchange, 204, "");
        } else if (path.endsWith("/password-reset")) {
            passwordResetRequests++;
            readText(exchange);
            respondJson(exchange, 202, "");
        } else if (path.endsWith("/password")) {
            String body = readText(exchange);
            if (!signedInPassword.equals(jsonText(body, "currentPassword"))) {
                respondJson(exchange, 401, "{}");
                return;
            }
            signedInPassword = jsonText(body, "newPassword");
            passwordChanges++;
            respondJson(exchange, 204, "");
        } else {
            respondJson(exchange, 404, "{}");
        }
    }

    private void usersSearch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/api/v1/users") && "GET".equals(method)) {
            String query = queryParam(exchange, "query");
            String items = query != null && query.toLowerCase().contains("bob") ? "[" + userJson(FRIEND_ID,
                    "bob", FRIEND_NAME) + "]" : "[]";
            respondJson(exchange, 200, "{\"items\":" + items + "}");
            return;
        }
        if (path.equals("/api/v1/users/me") && "GET".equals(method)) {
            respondJson(exchange, 200, profileJson());
            return;
        }
        if (path.equals("/api/v1/users/me") && "PATCH".equals(method)) {
            String body = readText(exchange);
            String fullName = jsonText(body, "fullName");
            if (fullName != null && !fullName.isBlank()) profileFullName = fullName;
            respondJson(exchange, 200, profileJson());
            return;
        }
        respondJson(exchange, 404, "{}");
    }

    private void friendRequests(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.endsWith("/outgoing") && "GET".equals(method)) {
            String items = outgoingRequests.entrySet().stream()
                    .map(entry -> "{\"id\":\"" + entry.getKey() + "\",\"to\":"
                            + userJson(entry.getValue(), "bob", FRIEND_NAME) + "}")
                    .reduce((first, second) -> first + "," + second).orElse("");
            respondJson(exchange, 200, "{\"items\":[" + items + "]}");
            return;
        }
        if (path.equals("/api/v1/friend-requests") && "GET".equals(method)) {
            String items = incomingRequests.entrySet().stream()
                    .map(entry -> "{\"id\":\"" + entry.getKey() + "\",\"from\":"
                            + userJson(entry.getValue(), "bob", FRIEND_NAME) + "}")
                    .reduce((first, second) -> first + "," + second).orElse("");
            respondJson(exchange, 200, "{\"items\":[" + items + "]}");
            return;
        }
        if (path.equals("/api/v1/friend-requests") && "POST".equals(method)) {
            String body = readText(exchange);
            String userId = jsonText(body, "userId");
            UUID target = userId == null ? FRIEND_ID : UUID.fromString(userId);
            if (blocked.contains(target)) {
                blockedRequestAttempts++;
                respondJson(exchange, 409, "{}");
                return;
            }
            outgoingRequests.put(UUID.randomUUID(), target);
            respondJson(exchange, 201, "");
            return;
        }
        UUID requestId = trailingUuid(path);
        if (requestId != null && "POST".equals(method)) {
            readText(exchange);
            incomingRequests.remove(requestId);
            respondJson(exchange, 204, "");
            return;
        }
        if (requestId != null && "DELETE".equals(method)) {
            outgoingRequests.remove(requestId);
            respondJson(exchange, 204, "");
            return;
        }
        respondJson(exchange, 404, "{}");
    }

    private void blocks(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/api/v1/blocks") && "GET".equals(method)) {
            String items = blocked.stream().map(id -> userJson(id, "bob", FRIEND_NAME))
                    .reduce((first, second) -> first + "," + second).orElse("");
            respondJson(exchange, 200, "{\"items\":[" + items + "]}");
            return;
        }
        UUID userId = trailingUuid(path);
        if (userId != null && "PUT".equals(method)) {
            blocked.add(userId);
            respondJson(exchange, 204, "");
            return;
        }
        if (userId != null && "DELETE".equals(method)) {
            blocked.remove(userId);
            respondJson(exchange, 204, "");
            return;
        }
        respondJson(exchange, 404, "{}");
    }

    private void reports(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        String body = readText(exchange);
        reports++;
        reportReason = jsonText(body, "reason");
        if (body.contains("selectedEvidence")) reportsWithEvidence++;
        respondJson(exchange, 201, "");
    }

    private void conversations(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        String direct = """
                {"id":"%s","name":"%s","type":"direct","createdAt":"2026-11-07T10:00:00Z"}
                """.formatted(CONVERSATION_ID, FRIEND_NAME);
        String group = """
                {"id":"%s","name":"%s","type":"group","createdAt":"2026-11-07T10:00:00Z"}
                """.formatted(GROUP_ID, escape(groupName));
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        if (directVisible) items.add(direct);
        if (groupCreated) items.add(group);
        respondJson(exchange, 200, "{\"items\":[" + String.join(",", items) + "]}");
    }

    private void conversationMessages(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/history") && "DELETE".equals(exchange.getRequestMethod())) {
            directMessages.clear();
            clearedHistories++;
            respondJson(exchange, 204, "");
            return;
        }
        if (path.contains("/messages/") && "DELETE".equals(exchange.getRequestMethod())) {
            deletedScopes.add(queryParam(exchange, "scope"));
            String id = path.substring(path.lastIndexOf('/') + 1);
            directMessages.removeIf(message -> message.contains("\"id\":\"" + id + "\""));
            respondJson(exchange, 204, "");
            return;
        }
        if (!path.endsWith("/messages")) {
            respondJson(exchange, 404, "{}");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 200, "{\"items\":[" + String.join(",", directMessages) + "]}");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        String content = jsonText(readText(exchange), "content");
        if (content == null || content.isBlank()) {
            respondJson(exchange, 400, "{}");
            return;
        }
        sentContents.add(content);
        String message = addDirectMessage(content);
        respondJson(exchange, 201, message);
    }

    private void friends(HttpExchange exchange) throws IOException {
        if ("DELETE".equals(exchange.getRequestMethod())) {
            friendRemovals++;
            respondJson(exchange, 204, "");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        respondJson(exchange, 200, """
                {"items":[{"id":"%s","username":"bob","fullName":"%s","presence":"online"}]}
                """.formatted(FRIEND_ID, FRIEND_NAME));
    }

    private void users(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v1/users/me") && "GET".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 200, profileJson());
            return;
        }
        if (path.equals("/api/v1/users/me") && "PATCH".equals(exchange.getRequestMethod())) {
            String body = readText(exchange);
            String fullName = jsonText(body, "fullName");
            if (fullName != null && !fullName.isBlank()) profileFullName = fullName;
            respondJson(exchange, 200, profileJson());
            return;
        }
        if (path.endsWith("/me/key-package") && "PUT".equals(exchange.getRequestMethod())) {
            readBytes(exchange);
            respondJson(exchange, 204, "");
            return;
        }
        if (path.endsWith("/key-package") && "GET".equals(exchange.getRequestMethod())) {
            if (friendKeyPackage.length == 0) {
                respondJson(exchange, 404, "{}");
            } else {
                respondBytes(exchange, 200, "application/octet-stream", friendKeyPackage);
            }
            return;
        }
        respondJson(exchange, 404, "{}");
    }

    private void createGroup(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, "{}");
            return;
        }
        readText(exchange);
        groupCreated = true;
        respondJson(exchange, 201, """
                {"id":"%s","name":"%s","type":"group","createdAt":"2026-11-07T10:00:00Z"}
                """.formatted(GROUP_ID, GROUP_NAME));
    }

    private void groups(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.matches("/api/v1/groups/[0-9a-fA-F-]{36}") && "PATCH".equals(method)) {
            String name = jsonText(readText(exchange), "name");
            if (name != null && !name.isBlank()) groupName = name;
            respondJson(exchange, 204, "");
            return;
        }
        if (path.endsWith("/role") && "PUT".equals(method)) {
            String body = readText(exchange);
            String role = jsonText(body, "role");
            String[] parts = path.split("/");
            UUID userId = UUID.fromString(parts[parts.length - 2]);
            memberRoles.put(userId, role);
            respondJson(exchange, 204, "");
            return;
        }
        if (path.matches("/api/v1/groups/[0-9a-fA-F-]{36}/members/[0-9a-fA-F-]{36}")
                && "DELETE".equals(method)) {
            String[] parts = path.split("/");
            removedMembers.add(UUID.fromString(parts[parts.length - 1]));
            respondJson(exchange, 204, "");
            return;
        }
        if (path.endsWith("/members") && "GET".equals(method)) {
            respondJson(exchange, 200, """
                    {"items":[
                      {"user":{"id":"%s","username":"alice","fullName":"Alice Example","presence":"online"},
                       "role":"%s","joinedEpoch":0},
                      {"user":{"id":"%s","username":"bob","fullName":"%s","presence":"online"},
                       "role":"%s","joinedEpoch":0}
                    ]}
                    """.formatted(USER_ID, memberRoles.getOrDefault(USER_ID, "admin"),
                    FRIEND_ID, FRIEND_NAME, memberRoles.getOrDefault(FRIEND_ID, "member")));
            return;
        }
        if (path.endsWith("/mls-commits") && "POST".equals(method)) {
            byte[] commit = readBytes(exchange);
            if (firstCommit == null) firstCommit = commit;
            long epoch = epochHeader(exchange) + 1;
            groupMessages.add(new MlsWireMessage(UUID.randomUUID(), epoch, "commit", commit));
            respondJson(exchange, 204, "");
            return;
        }
        if (path.endsWith("/mls-messages") && "GET".equals(method)) {
            respondJson(exchange, 200, "{\"items\":[" + groupMessagesJson() + "]}");
            return;
        }
        if (path.endsWith("/mls-messages") && "POST".equals(method)) {
            byte[] ciphertext = readBytes(exchange);
            UUID id = UUID.randomUUID();
            long epoch = epochHeader(exchange);
            groupMessages.add(new MlsWireMessage(id, epoch, "application", ciphertext));
            respondJson(exchange, 201, mlsMessageJson(id, epoch, "application", ciphertext));
            return;
        }
        if (path.endsWith("/recovery-package") && "PUT".equals(method)) {
            readBytes(exchange);
            respondJson(exchange, 204, "");
            return;
        }
        if (path.endsWith("/recovery-package") && "GET".equals(method)) {
            respondJson(exchange, 404, "{}");
            return;
        }
        respondJson(exchange, 404, "{}");
    }

    private String groupMessagesJson() {
        return groupMessages.stream()
                .map(message -> mlsMessageJson(message.id(), message.epoch(), message.kind(), message.ciphertext()))
                .reduce((first, second) -> first + "," + second)
                .orElse("");
    }

    private String mlsMessageJson(UUID id, long epoch, String kind, byte[] ciphertext) {
        return """
                {"id":"%s","epoch":%d,"kind":"%s","ciphertext":"%s",
                 "createdAt":"2026-11-07T10:00:00Z"}
                """.formatted(id, epoch, kind, Base64.getEncoder().encodeToString(ciphertext));
    }

    private String messageJson(UUID id, UUID senderId, String content) {
        return """
                {"id":"%s","conversationId":"%s","senderId":"%s","content":"%s",
                 "createdAt":"2026-11-07T10:00:00Z"}
                """.formatted(id, CONVERSATION_ID, senderId, escape(content));
    }

    private String profileJson() {
        String fullName = profileFullName == null ? "Alice Example" : profileFullName;
        return """
                {"id":"%s","username":"alice","fullName":"%s","email":"alice@example.test",
                 "address":"1 Test Road","birthDate":"1990-01-01","gender":"other",
                 "presence":"online"}
                """.formatted(USER_ID, escape(fullName));
    }

    private static String userJson(UUID id, String username, String fullName) {
        return """
                {"id":"%s","username":"%s","fullName":"%s","presence":"online"}
                """.formatted(id, username, escape(fullName));
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static UUID trailingUuid(String path) {
        String value = path.substring(path.lastIndexOf('/') + 1);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static long epochHeader(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("X-MLS-Epoch");
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }

    private static byte[] defaultFriendKeyPackage() throws IOException {
        try {
            return MlsKeyPackage.create(MlsIdentity.generate(FRIEND_ID, "desktop-friend")).encode();
        } catch (MlsException exception) {
            throw new IOException("Could not create the test key package.", exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String jsonText(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) return null;
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r");
    }

    private static String readText(HttpExchange exchange) throws IOException {
        return new String(readBytes(exchange), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(HttpExchange exchange) throws IOException {
        return exchange.getRequestBody().readAllBytes();
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        respondBytes(exchange, status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record MlsWireMessage(UUID id, long epoch, String kind, byte[] ciphertext) {
        private MlsWireMessage {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    private record FakeAccount(UUID id, String username, String fullName, String email,
                               String address, String birthDate, String gender, String password) {
        private String profileJson() {
            return """
                    {"id":"%s","username":"%s","fullName":"%s","email":"%s",
                     "address":"%s","birthDate":"%s","gender":"%s","presence":"online"}
                    """.formatted(id, escape(username), escape(fullName), escape(email),
                    escape(address == null ? "" : address),
                    birthDate == null ? "1995-01-01" : birthDate,
                    gender == null ? "undisclosed" : gender);
        }
    }
}
