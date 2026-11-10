package com.haichutieu.chatsystem.server;

import com.haichutieu.chatsystem.dto.ChatList;
import com.haichutieu.chatsystem.dto.Conversation;
import com.haichutieu.chatsystem.dto.Customer;
import com.haichutieu.chatsystem.dto.MessageConversation;
import com.haichutieu.chatsystem.integration.PostgresTestBase;
import com.haichutieu.chatsystem.server.dal.AdminService;
import com.haichutieu.chatsystem.server.dal.CustomerService;
import com.haichutieu.chatsystem.server.dal.MessageService;
import com.haichutieu.chatsystem.util.Util;
import com.haichutieu.chatsystem.util.PasswordUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real asynchronous socket server on an ephemeral port and talks to it
 * over TCP. The database is the PostgreSQL 18 container from PostgresTestBase.
 */
class SocketServerIntegrationTest extends PostgresTestBase {

    private static SocketServer server;
    private static int port;

    @BeforeAll
    static void startServer() {
        Properties properties = new Properties();
        properties.putAll(PROPERTIES);
        properties.setProperty("server.port", "0");
        server = new SocketServer(properties, false);
        port = server.getPort();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void shouldServeMultipleClientsAndSplitPipelinedCommands() throws Exception {
        try (Client first = new Client(port); Client second = new Client(port); Client third = new Client(port)) {
            first.send("GET_ONLINE_USERS null");
            second.send("GET_ONLINE_USERS null\nGET_ONLINE_USERS null");
            third.send("GET_ONLINE_USERS null");

            assertThat(first.await(line -> line.startsWith("GET_ONLINE_USERS "))).startsWith("GET_ONLINE_USERS ");
            assertThat(second.await(line -> line.startsWith("GET_ONLINE_USERS "))).startsWith("GET_ONLINE_USERS ");
            assertThat(second.await(line -> line.startsWith("GET_ONLINE_USERS "))).startsWith("GET_ONLINE_USERS ");
            assertThat(third.await(line -> line.startsWith("GET_ONLINE_USERS "))).startsWith("GET_ONLINE_USERS ");
        }
    }

    @Test
    void shouldRejectWrongPasswordAndLockedAccount() throws Exception {
        Customer customer = createCustomer("lockeduser");
        CustomerService.toggleLockStatusAccount(customer.getId());

        try (Client client = new Client(port)) {
            client.send("LOGIN NULL " + Util.serializeObject(List.of("lockeduser", "wrong-password")));
            assertThat(client.await(line -> line.startsWith("LOGIN "))).startsWith("LOGIN ERROR");

            client.send("LOGIN NULL " + Util.serializeObject(List.of("lockeduser", "password123")));
            assertThat(client.await(line -> line.startsWith("LOGIN "))).contains("locked");
        }
    }

    @Test
    void shouldRegisterAndRejectDuplicateUsernameOrEmail() throws Exception {
        try (Client first = new Client(port); Client second = new Client(port)) {
            first.send("REGISTER NULL " + Util.serializeObject(List.of(
                    "New User", "newuser", "new@example.com", "password123")));
            assertThat(first.await(line -> line.startsWith("REGISTER ")))
                    .contains("newuser")
                    .contains("new@example.com");

            second.send("REGISTER NULL " + Util.serializeObject(List.of(
                    "Other", "newuser", "other@example.com", "password123")));
            assertThat(second.await(line -> line.startsWith("REGISTER ")))
                    .isEqualTo("REGISTER ERROR Username already exists");

            second.send("REGISTER NULL " + Util.serializeObject(List.of(
                    "Other", "otheruser", "new@example.com", "password123")));
            assertThat(second.await(line -> line.startsWith("REGISTER ")))
                    .isEqualTo("REGISTER ERROR Email already exists");
        }

        Customer stored = CustomerService.getCustomerByUsername("newuser");
        assertThat(stored).isNotNull();
        assertThat(stored.getEmail()).isEqualTo("new@example.com");
        assertThat(PasswordUtil.verify("password123", stored.getPassword())).isTrue();
    }

    @Test
    void shouldPersistAndBroadcastDirectMessage() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());

        try (Client aliceClient = new Client(port); Client bobClient = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");

            ChatList conversation = new ChatList();
            conversation.conversationID = conversationId;
            conversation.conversationName = "Bob";
            MessageConversation message = new MessageConversation(
                    conversationId, alice.getId(), "Alice",
                    new Timestamp(System.currentTimeMillis()), "hello over tcp");

            aliceClient.send("MESSAGE " + Util.serializeObject(conversation)
                    + " END " + Util.serializeObject(message));

            assertThat(aliceClient.await(line -> line.startsWith("MESSAGE "))).contains("hello over tcp");
            assertThat(bobClient.await(line -> line.startsWith("MESSAGE "))).contains("hello over tcp");
            assertThat(MessageService.getMessageConversation(conversationId, bob.getId()))
                    .extracting(stored -> stored.message)
                    .containsExactly("hello over tcp");
        }
    }

    @Test
    void shouldRejectAdminCommandsUntilAnAdminSessionIsEstablished() throws Exception {
        createAdmin("adminuser");

        try (Client user = new Client(port); Client admin = new Client(port)) {
            user.send("FETCH_ACCOUNT_LIST null");
            assertThat(user.await(line -> line.startsWith("FETCH_ACCOUNT_LIST ")))
                    .contains("Unauthorized");

            admin.send("LOGIN_ADMIN adminuser password123");
            assertThat(admin.await(line -> line.startsWith("LOGIN_ADMIN ")))
                    .startsWith("LOGIN_ADMIN OK");

            admin.send("FETCH_ACCOUNT_LIST null");
            assertThat(admin.await(line -> line.startsWith("FETCH_ACCOUNT_LIST ")))
                    .startsWith("FETCH_ACCOUNT_LIST OK");
        }

        try (Client afterDisconnect = new Client(port)) {
            afterDisconnect.send("FETCH_ACCOUNT_LIST null");
            assertThat(afterDisconnect.await(line -> line.startsWith("FETCH_ACCOUNT_LIST ")))
                    .contains("Unauthorized");
        }
    }

    @Test
    void shouldServeAdminReadAndAccountManagementCommands() throws Exception {
        Customer managed = createCustomer("manageduser");
        Customer peer = createCustomer("peeruser");
        createAdmin("adminuser");

        try (Client admin = new Client(port)) {
            admin.send("LOGIN_ADMIN adminuser password123");
            assertThat(admin.await(line -> line.startsWith("LOGIN_ADMIN ")))
                    .startsWith("LOGIN_ADMIN OK");

            admin.send("FETCH_ACCOUNT_LIST null");
            assertThat(admin.await(line -> line.startsWith("FETCH_ACCOUNT_LIST ")))
                    .startsWith("FETCH_ACCOUNT_LIST OK")
                    .contains("manageduser");

            admin.send("FETCH_NEW_ACCOUNTS 5");
            assertThat(admin.await(line -> line.startsWith("FETCH_NEW_ACCOUNTS ")))
                    .startsWith("FETCH_NEW_ACCOUNTS OK");

            admin.send("FETCH_GROUP_LIST null");
            assertThat(admin.await(line -> line.startsWith("FETCH_GROUP_LIST ")))
                    .startsWith("FETCH_GROUP_LIST ");

            long conversationId = MessageService.createSingleConversation(managed.getId(), peer.getId());
            admin.send("FETCH_MEMBER_LIST " + conversationId);
            assertThat(admin.await(line -> line.startsWith("FETCH_MEMBER_LIST ")))
                    .contains("manageduser");

            admin.send("LOGIN_HISTORY ALL 10");
            assertThat(admin.await(line -> line.startsWith("LOGIN_HISTORY ")))
                    .startsWith("LOGIN_HISTORY ALL OK");

            admin.send("LOGIN_HISTORY USER " + managed.getId());
            assertThat(admin.await(line -> line.startsWith("LOGIN_HISTORY ")))
                    .startsWith("LOGIN_HISTORY USER OK");

            admin.send("SPAM_LIST ALL");
            assertThat(admin.await(line -> line.startsWith("SPAM_LIST ")))
                    .startsWith("SPAM_LIST OK");

            admin.send("FRIEND_COUNT null");
            assertThat(admin.await(line -> line.startsWith("FRIEND_COUNT ")))
                    .startsWith("FRIEND_COUNT OK");

            admin.send("FETCH_ONLINE_USER_COUNT_LIST null");
            assertThat(admin.await(line -> line.startsWith("FETCH_ONLINE_USER_COUNT_LIST ")))
                    .startsWith("FETCH_ONLINE_USER_COUNT_LIST ");

            admin.send("FETCH_ONLINE_USER_COUNT_TIME_RANGE_LIST 2020-01-01 00:00:00 END 2030-01-01 00:00:00");
            assertThat(admin.await(line -> line.startsWith("FETCH_ONLINE_USER_COUNT_LIST ")))
                    .startsWith("FETCH_ONLINE_USER_COUNT_LIST ");

            admin.send("FETCH_NEW_USERS_MONTHLY null");
            assertThat(admin.await(line -> line.startsWith("FETCH_NEW_USERS_MONTHLY ")))
                    .startsWith("FETCH_NEW_USERS_MONTHLY ");

            admin.send("FETCH_APP_USAGE_MONTHLY null");
            assertThat(admin.await(line -> line.startsWith("FETCH_APP_USAGE_MONTHLY ")))
                    .startsWith("FETCH_APP_USAGE_MONTHLY ");

            admin.send("TOGGLE_ACCOUNT_STATUS " + managed.getId());
            assertThat(admin.await(line -> line.startsWith("TOGGLE_ACCOUNT_STATUS ")))
                    .startsWith("TOGGLE_ACCOUNT_STATUS OK");
            assertThat(CustomerService.getCustomerByID(managed.getId()).getIsLock()).isTrue();

            String newHash = PasswordUtil.hash("changed-password");
            admin.send("CHANGE_PASSWORD " + managed.getId() + " " + newHash);
            assertThat(admin.await(line -> line.startsWith("CHANGE_PASSWORD ")))
                    .startsWith("CHANGE_PASSWORD OK");
            assertThat(PasswordUtil.verify("changed-password",
                    CustomerService.getCustomerByID(managed.getId()).getPassword())).isTrue();

            admin.send("DELETE_ACCOUNT " + managed.getId());
            assertThat(admin.await(line -> line.startsWith("DELETE_ACCOUNT ")))
                    .startsWith("DELETE_ACCOUNT OK");
            assertThat(CustomerService.getCustomerByID(managed.getId())).isNull();
        }
    }

    @Test
    void shouldKeepConnectionAliveAfterMalformedOrUnknownCommands() throws Exception {
        try (Client client = new Client(port)) {
            client.send("LOGIN NULL {not-json");
            assertThat(client.await(line -> line.startsWith("LOGIN ")))
                    .startsWith("LOGIN ERROR Invalid request");

            client.send("BOGUS");
            assertThat(client.await(line -> line.startsWith("BOGUS ")))
                    .startsWith("BOGUS ERROR Invalid request");

            client.send("GET_ONLINE_USERS null");
            assertThat(client.await(line -> line.startsWith("GET_ONLINE_USERS ")))
                    .startsWith("GET_ONLINE_USERS ");
        }
    }

    @Test
    void shouldDeliverLargeMultibyteMessageIntact() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());
        String text = "chào ".repeat(700);

        try (Client aliceClient = new Client(port); Client bobClient = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");

            ChatList conversation = new ChatList();
            conversation.conversationID = conversationId;
            conversation.conversationName = "Bob";
            MessageConversation message = new MessageConversation(
                    conversationId, alice.getId(), "Alice",
                    new Timestamp(System.currentTimeMillis()), text);

            aliceClient.send("MESSAGE " + Util.serializeObject(conversation)
                    + " END " + Util.serializeObject(message));

            assertThat(aliceClient.await(line -> line.startsWith("MESSAGE "))).contains(text);
            assertThat(bobClient.await(line -> line.startsWith("MESSAGE "))).contains(text);
        }
    }

    @Test
    void shouldBroadcastOfflineWhenAChannelClosesAbruptly() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");

        try (Client aliceClient = new Client(port); Client bobClient = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");

            aliceClient.close();
            assertThat(bobClient.await(line -> line.equals("OFFLINE " + alice.getId())))
                    .isEqualTo("OFFLINE " + alice.getId());

            try (Client reconnected = new Client(port)) {
                login(reconnected, "alice");
            }
        }
    }

    @Test
    void shouldPreserveOrderOfSequentialMessages() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());
        ChatList conversation = new ChatList();
        conversation.conversationID = conversationId;
        conversation.conversationName = "Bob";

        try (Client aliceClient = new Client(port); Client bobClient = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");

            for (int i = 0; i < 5; i++) {
                aliceClient.send("MESSAGE " + Util.serializeObject(conversation)
                        + " END " + Util.serializeObject(new MessageConversation(
                        conversationId, alice.getId(), "Alice",
                        new Timestamp(System.currentTimeMillis() + i), "ordered-" + i)));
            }

            for (int i = 0; i < 5; i++) {
                int expected = i;
                assertThat(bobClient.await(line -> line.startsWith("MESSAGE ")))
                        .contains("ordered-" + expected);
            }
        }
    }

    @Test
    void shouldOnlyAllowTheSenderToDeleteAMessageForEveryone() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        Customer outsider = createCustomer("outsider");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());
        MessageConversation stored = new MessageConversation(
                conversationId, alice.getId(), "Alice",
                new Timestamp(System.currentTimeMillis()), "delete me");
        stored.id = MessageService.addMessage(stored, List.of(alice.getId(), bob.getId()));

        try (Client aliceClient = new Client(port);
             Client bobClient = new Client(port);
             Client outsiderClient = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");
            login(outsiderClient, "outsider");

            outsiderClient.send("REMOVE_MESSAGE_ALL " + Util.serializeObject(stored));
            assertThat(outsiderClient.await(line -> line.startsWith("REMOVE_MESSAGE_ALL ")))
                    .contains("Unauthorized");

            bobClient.send("REMOVE_MESSAGE_ALL " + Util.serializeObject(stored));
            assertThat(bobClient.await(line -> line.startsWith("REMOVE_MESSAGE_ALL ")))
                    .contains("Unauthorized");
            assertThat(MessageService.getMessageConversation(conversationId, bob.getId())).hasSize(1);

            aliceClient.send("REMOVE_MESSAGE_ALL " + Util.serializeObject(stored));
            assertThat(aliceClient.await(line -> line.startsWith("REMOVE_MESSAGE_ALL ")))
                    .startsWith("REMOVE_MESSAGE_ALL ");
            assertThat(MessageService.getMessageConversation(conversationId, bob.getId())).isEmpty();
        }
    }

    @Test
    void shouldOnlyAllowAUserToUpdateTheirOwnAccount() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");

        Customer patch = new Customer();
        patch.setId(alice.getId());
        patch.setUsername("alice");
        patch.setEmail("alice@example.com");
        patch.setName("Alice Updated");
        patch.setAddress("New address");

        try (Client aliceClient = new Client(port);
             Client bobClient = new Client(port);
             Client anonymous = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");

            aliceClient.send("UPDATE_ACCOUNT " + Util.serializeObject(patch));
            assertThat(aliceClient.await(line -> line.startsWith("UPDATE_ACCOUNT ")))
                    .contains("Update account successfully");
            Customer updated = CustomerService.getCustomerByID(alice.getId());
            assertThat(updated.getName()).isEqualTo("Alice Updated");
            assertThat(updated.getAddress()).isEqualTo("New address");

            bobClient.send("UPDATE_ACCOUNT " + Util.serializeObject(patch));
            assertThat(bobClient.await(line -> line.startsWith("UPDATE_ACCOUNT ")))
                    .contains("Unauthorized");

            anonymous.send("UPDATE_ACCOUNT " + Util.serializeObject(patch));
            assertThat(anonymous.await(line -> line.startsWith("UPDATE_ACCOUNT ")))
                    .contains("Unauthorized");
        }
    }

    @Test
    void shouldUpgradeLegacyBcryptHashAfterSuccessfulLogin() throws Exception {
        Customer legacy = createLegacyCustomer("legacyuser");

        try (Client client = new Client(port)) {
            login(client, "legacyuser", "admin123");
        }

        String upgraded = CustomerService.getCustomerByID(legacy.getId()).getPassword();
        assertThat(upgraded).startsWith("$argon2id$");
        assertThat(PasswordUtil.verify("admin123", upgraded)).isTrue();
    }

    @Test
    void shouldHandleConcurrentMessagesWithoutLoss() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());

        try (Client aliceClient = new Client(port); Client bobClient = new Client(port)) {
            login(aliceClient, "alice");
            login(bobClient, "bob");

            ChatList conversation = new ChatList();
            conversation.conversationID = conversationId;
            conversation.conversationName = "Bob";

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> aliceSend = pool.submit((Callable<Void>) () -> {
                    sendMessages(aliceClient, conversation, alice.getId(), "alice", 10);
                    return null;
                });
                Future<?> bobSend = pool.submit((Callable<Void>) () -> {
                    sendMessages(bobClient, conversation, bob.getId(), "bob", 10);
                    return null;
                });
                aliceSend.get(20, TimeUnit.SECONDS);
                bobSend.get(20, TimeUnit.SECONDS);
            }

            assertThat(aliceClient.awaitCount(line -> line.startsWith("MESSAGE "), 20)).isEqualTo(20);
            assertThat(bobClient.awaitCount(line -> line.startsWith("MESSAGE "), 20)).isEqualTo(20);
            assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).hasSize(20);
        }
    }

    @Test
    void shouldEnforceGroupMembershipAndAdminForManagement() throws Exception {
        Customer owner = createCustomer("owner");
        Customer member = createCustomer("member");
        Customer outsider = createCustomer("outsider");
        Conversation group = new Conversation();
        group.setName("Team");
        group.setIsGroup(true);
        group.setCreateDate(new Timestamp(System.currentTimeMillis()));
        long conversationId = MessageService.createGroupConversation(
                owner.getId(), group, List.of(member.getId()));

        try (Client ownerClient = new Client(port);
             Client memberClient = new Client(port);
             Client outsiderClient = new Client(port)) {
            login(ownerClient, "owner");
            login(memberClient, "member");
            login(outsiderClient, "outsider");

            memberClient.send("GROUP UPDATE_NAME " + conversationId + " Hacked");
            assertThat(memberClient.await(line -> line.startsWith("GROUP ")))
                    .contains("Unauthorized");

            outsiderClient.send("GROUP GET_MEMBERS " + conversationId + " null");
            assertThat(outsiderClient.await(line -> line.startsWith("GROUP ")))
                    .contains("Unauthorized");

            memberClient.send("GROUP GET_MEMBERS " + conversationId + " null");
            assertThat(memberClient.await(line -> line.startsWith("GROUP GET_MEMBERS ")))
                    .startsWith("GROUP GET_MEMBERS OK");

            memberClient.send("GROUP REMOVE_MEMBER " + conversationId + " " + owner.getId());
            assertThat(memberClient.await(line -> line.startsWith("GROUP ")))
                    .contains("Unauthorized");

            ownerClient.send("GROUP UPDATE_NAME " + conversationId + " Renamed");
            assertThat(ownerClient.await(line -> line.startsWith("GROUP UPDATE_NAME ")))
                    .startsWith("GROUP UPDATE_NAME OK");
        }

        assertThat(AdminService.getConversation())
                .filteredOn(conversation -> conversation.getId() == conversationId)
                .extracting(Conversation::getName)
                .containsExactly("Renamed");
    }

    @Test
    void shouldRejectMessagesFromNonMembersAndSpoofedSenders() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        Customer outsider = createCustomer("outsider");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());
        ChatList conversation = new ChatList();
        conversation.conversationID = conversationId;
        conversation.conversationName = "Bob";

        try (Client aliceClient = new Client(port); Client outsiderClient = new Client(port)) {
            login(aliceClient, "alice");
            login(outsiderClient, "outsider");

            MessageConversation injected = new MessageConversation(
                    conversationId, outsider.getId(), "Outsider",
                    new Timestamp(System.currentTimeMillis()), "injected");
            outsiderClient.send("MESSAGE " + Util.serializeObject(conversation)
                    + " END " + Util.serializeObject(injected));
            assertThat(outsiderClient.await(line -> line.startsWith("MESSAGE ")))
                    .contains("Unauthorized");

            MessageConversation spoofed = new MessageConversation(
                    conversationId, bob.getId(), "Bob",
                    new Timestamp(System.currentTimeMillis()), "spoofed");
            aliceClient.send("MESSAGE " + Util.serializeObject(conversation)
                    + " END " + Util.serializeObject(spoofed));
            assertThat(aliceClient.await(line -> line.startsWith("MESSAGE ")))
                    .contains("Unauthorized");

            assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).isEmpty();
        }
    }

    private static void login(Client client, String username) throws Exception {
        login(client, username, "password123");
    }

    private static void login(Client client, String username, String password) throws Exception {
        client.send("LOGIN NULL " + Util.serializeObject(List.of(username, password)));
        assertThat(client.await(line -> line.startsWith("LOGIN "))).startsWith("LOGIN ");
    }

    private static void sendMessages(Client client, ChatList conversation, int senderId,
                                     String senderName, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            MessageConversation message = new MessageConversation(
                    conversation.conversationID, senderId, senderName,
                    new Timestamp(System.currentTimeMillis() + i), senderName + "-" + i);
            client.send("MESSAGE " + Util.serializeObject(conversation)
                    + " END " + Util.serializeObject(message));
        }
    }

    private static final class Client implements AutoCloseable {

        private final AsynchronousSocketChannel channel;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        private Client(int port) throws Exception {
            channel = AsynchronousSocketChannel.open();
            channel.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                    .get(10, TimeUnit.SECONDS);
            Thread.startVirtualThread(this::readLoop);
        }

        private void send(String message) throws Exception {
            channel.write(ByteBuffer.wrap((message + "\n").getBytes(StandardCharsets.UTF_8)))
                    .get(10, TimeUnit.SECONDS);
        }

        private String await(Predicate<String> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && predicate.test(line)) {
                    return line;
                }
            }
            throw new AssertionError("No matching line within 15 seconds; buffered lines: " + lines);
        }

        private int awaitCount(Predicate<String> predicate, int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int count = 0;
            while (System.nanoTime() < deadline && count < expected) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && predicate.test(line)) {
                    count++;
                }
            }
            return count;
        }

        private void readLoop() {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try {
                while (channel.read(buffer).get() != -1) {
                    buffer.flip();
                    byte[] bytes = new byte[buffer.limit()];
                    buffer.get(bytes);
                    buffer.clear();
                    received.write(bytes);
                    String line;
                    while ((line = Util.readLine(received)) != null) {
                        lines.add(line);
                    }
                }
            } catch (Exception ignored) {
                // the test closes the channel
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
