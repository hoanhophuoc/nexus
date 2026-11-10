package com.haichutieu.chatsystem.server;

import tools.jackson.core.type.TypeReference;
import com.haichutieu.chatsystem.dto.*;
import com.haichutieu.chatsystem.server.dal.*;
import com.haichutieu.chatsystem.util.Config;
import com.haichutieu.chatsystem.util.PasswordUtil;
import com.haichutieu.chatsystem.util.Util;

import jakarta.mail.Message;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class SocketServer {
    private final Map<Integer, AsynchronousSocketChannel> onlineUsers = new ConcurrentHashMap<>(); // Map<user_id, AsynchronousSocketChannel>
    private final Set<AsynchronousSocketChannel> adminChannels = ConcurrentHashMap.newKeySet();
    private final int bufferSize = 1024;
    private AsynchronousServerSocketChannel serverChannel;
    public static Properties properties;

    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "FETCH_ACCOUNT_LIST", "FETCH_NEW_ACCOUNTS", "FETCH_GROUP_LIST", "FETCH_MEMBER_LIST",
            "ADD_ACCOUNT", "DELETE_ACCOUNT", "EDIT_ACCOUNT", "TOGGLE_ACCOUNT_STATUS",
            "CHANGE_PASSWORD", "LOGIN_HISTORY", "SPAM_LIST", "DELETE_SPAM", "LOCK_ACCOUNT",
            "FRIEND_COUNT", "FETCH_ONLINE_USER_COUNT_LIST", "FETCH_ONLINE_USER_COUNT_TIME_RANGE_LIST",
            "FETCH_NEW_USERS_MONTHLY", "FETCH_APP_USAGE_MONTHLY"
    );

    private SocketServer() {
        this(loadProperties(), true);
    }

    private static Properties loadProperties() {
        // Packaged defaults, optionally overridden by server-config.properties in the
        // working directory and by environment variables (which always win).
        return Config.load("server-config.properties", "server-config.properties", Map.of(
                "hibernate.hikari.dataSource.url", "CHAT_DB_URL",
                "hibernate.hikari.dataSource.user", "CHAT_DB_USER",
                "hibernate.hikari.dataSource.password", "CHAT_DB_PASSWORD",
                "hibernate.hbm2ddl.auto", "CHAT_DB_DDL_AUTO",
                "server.host", "CHAT_SERVER_HOST",
                "server.port", "CHAT_SERVER_PORT",
                "email.username", "CHAT_EMAIL_USERNAME",
                "email.password", "CHAT_EMAIL_PASSWORD"
        ));
    }

    /**
     * @param block when true the calling thread is kept alive while the server runs;
     *              integration tests start the server on port 0 with {@code false}
     */
    SocketServer(Properties properties, boolean block) {
        SocketServer.properties = properties;
        try {
            final String HOST = properties.getProperty("server.host");
            final int PORT = Integer.parseInt(properties.getProperty("server.port"));

            HibernateUtil.getInstance();
            serverChannel = AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(HOST, PORT));
            System.out.println("Server started at " + HOST + ":" + getPort());
            // Accept connections
            serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel clientChannel, Void attachment) {
                    // Handle this connection
                    handleClient(clientChannel);
                    // Accept the next connection
                    serverChannel.accept(null, this);
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    exc.printStackTrace();
                }
            });
            // Keep the main thread alive
            if (block) {
                Thread.currentThread().join();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start the chat server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the chat server", e);
        }
    }

    int getPort() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("The server channel is not bound", e);
        }
    }

    void close() {
        try {
            serverChannel.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close the server channel", e);
        }
        onlineUsers.values().forEach(channel -> {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best-effort shutdown of client connections
            }
        });
        onlineUsers.clear();
        adminChannels.clear();
    }

    public static SocketServer getInstance() {
        return SocketServerHelper.INSTANCE;
    }

    private void handleClient(AsynchronousSocketChannel clientChannel) {
        ByteArrayOutputStream clientData = new ByteArrayOutputStream();
        Thread.startVirtualThread(() -> {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            try {
                while (clientChannel.read(buffer).get() != -1) {
                    buffer.flip();
                    byte[] bytes = new byte[buffer.limit()];
                    buffer.get(bytes);
                    buffer.clear();
                    clientData.write(bytes);

                    String line;
                    while ((line = Util.readLine(clientData)) != null) {
                        System.out.println("[SERVER]: Received " + line);
                        String response = processInput(line, clientChannel);
                        if (response != null) {
                            System.out.println("[SERVER]: Sending " + response);
                            writeResponse(clientChannel, response);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
            } finally {
                Integer disconnectedUser = onlineUsers.entrySet().stream()
                        .filter(entry -> entry.getValue() == clientChannel)
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
                if (disconnectedUser != null) {
                    onlineUsers.remove(disconnectedUser, clientChannel);
                    broadcast("OFFLINE " + disconnectedUser);
                }
                adminChannels.remove(clientChannel);
                try {
                    clientChannel.close();
                } catch (IOException ignored) {
                    // connection is already gone
                }
            }
        });
    }

    private String processInput(String input, AsynchronousSocketChannel clientChannel) {
        String[] parts = input.split(" ", 2);
        String command = parts[0];
        String content = parts.length > 1 ? parts[1] : "";
        if (ADMIN_COMMANDS.contains(command) && !adminChannels.contains(clientChannel)) {
            return command + " ERROR Unauthorized";
        }
        try {
        switch (command) {
            case "REGISTER":
                return handleRegister(content, clientChannel);
            case "LOGIN":
                return handleLogin(content, clientChannel);
            case "RESET_PASSWORD":
                return handleForgotPassword(content);
            case "SEARCH_USER":
                return handleSearchUser(content);
            case "ADD_FRIEND":
                return handleAddFriend(content);
            case "GET_FRIEND_REQUEST":
                return handleGetFriendRequest(content);
            case "ANSWER_INVITATION":
                return handleAnswerInvitation(content);
            case "SPAM":
                return handleSpamReport(content);
            case "SPAM_CONVERSATION":
                return handleSpamConversation(content);
            case "BLOCK":
                return handleBlock(content);
            case "CHAT_LIST":
                return getChatList(content);
            case "GET_ONLINE_USERS":
                return getOnlineUsers();
            case "GET_ALL_MEMBER_CONVERSATION":
                return getAllMemberConversation(content);
            case "GET_MESSAGE_CONVERSATION":
                return getMessageConversation(content);
            case "UPDATE_STATUS_CONVERSATION":
                return updateStatusConversation(content);
            case "MESSAGE":
                return handleMessage(content, clientChannel);
            case "REMOVE_MESSAGE_ME":
                return removeMessageMe(content, clientChannel);
            case "REMOVE_MESSAGE_ALL":
                return removeMessageAll(content, clientChannel);
            case "REMOVE_ALL_MESSAGE_ME":
                return removeAllMessageMe(content, clientChannel);
            case "UPDATE_ACCOUNT":
                return updateAccount(content, clientChannel);
            case "GET_FRIEND_LIST":
                return handleGetFriendList(content);
            case "UNFRIEND":
                return handleUnfriend(content);
            case "OFFLINE":
                handleOffline(content, clientChannel); // user exit or logout
                break;
            case "CREATE_GROUP":
                return handleCreateGroup(content);
            case "GROUP":
                return handleGroup(content, clientChannel);
            case "OPEN_CHAT":
                return handleOpenChat(content);
            // Admin commands
            case "LOGIN_ADMIN":
                return handleAdminLogin(content, clientChannel);
            case "FETCH_ACCOUNT_LIST":
                return handleFetchAccountList();
            case "FETCH_NEW_ACCOUNTS":
                return handleFetchNewAccount(content);
            case "FETCH_GROUP_LIST":
                return handleFetchGroupList();
            case "FETCH_MEMBER_LIST":
                return handleFetchMemberList(content);
            case "ADD_ACCOUNT":
                return handleAddAccount(content);
            case "DELETE_ACCOUNT":
                return handleDeleteAccount(content);
            case "EDIT_ACCOUNT":
                return handleEditAccount(content);
            case "TOGGLE_ACCOUNT_STATUS":
                return handleToggleLockAccount(content);
            case "CHANGE_PASSWORD":
                return handleChangePassword(content);
//            case "RESET_PASSWORD":
//                return handleResetPassword(content);
            case "LOGIN_HISTORY":
                return handleLoginHistory(content);
//            case "USER_FRIEND_LIST" -> handleUserFriendList(content);
            case "SPAM_LIST":
                return handleSpamList(content);
            case "DELETE_SPAM":
                return handleDeleteSpam(content);
            case "LOCK_ACCOUNT":
                return handleLockAccount(content);
            case "FRIEND_COUNT":
                return handleFriendCount();
            case "FETCH_ONLINE_USER_COUNT_LIST":
                return handleOnlineUserCountList();
            case "FETCH_ONLINE_USER_COUNT_TIME_RANGE_LIST":
                return handleOnlineUserCountTimeRangeList(content);
            case "FETCH_NEW_USERS_MONTHLY":
                return handleNewUsersMonthly();
            case "FETCH_APP_USAGE_MONTHLY":
                return handleAppUsageMonthly();
            default:
                throw new IllegalStateException("Unexpected value: " + command);
        }
        return null;
        } catch (RuntimeException e) {
            return command + " ERROR Invalid request";
        }
    }

    private String handleSpamConversation(String content) {
        String[] parts = content.split(" ", 2);
        int userID = Integer.parseInt(parts[0]);
        long conversationID = Long.parseLong(parts[1]);

        List<Integer> members = MessageService.getMemberConversation(conversationID);
        assert members != null;
        int reportedUserId = members.stream().filter(member -> member != userID).findFirst().orElse(-1);

        if (reportedUserId == -1) {
            return "SPAM_CONVERSATION ERROR Failed to report conversation";
        }

        if (!FriendsService.reportSpam(userID, reportedUserId)) {
            return "SPAM_CONVERSATION ERROR Failed to report conversation";
        }

        return "SPAM_CONVERSATION OK " + reportedUserId;
    }

    private String handleAnswerInvitation(String content) {
        String[] parts = content.split(" ");
        String type = parts[0];
        int userID = Integer.parseInt(parts[1]);
        int friendID = Integer.parseInt(parts[2]);

        Customer friend = CustomerService.getCustomerByID(friendID);
        Customer user = CustomerService.getCustomerByID(userID);

        if (type.equals("ACCEPT")) {
            if (!FriendsService.acceptFriend(userID, friendID)) {
                return "ANSWER_INVITATION ACCEPT ERROR " + friendID;
            }

            sendToUser(friendID, "ANSWER_INVITATION ACCEPT FROM " + Util.serializeObject(user));

            return "ANSWER_INVITATION ACCEPT OK " + Util.serializeObject(friend);
        } else if (type.equals("REJECT")) {
            if (!FriendsService.rejectFriend(userID, friendID)) {
                return "ANSWER_INVITATION REJECT ERROR " + friendID;
            }

            assert user != null;
            sendToUser(friendID, "ANSWER_INVITATION REJECT FROM " + user.getUsername());

            return "ANSWER_INVITATION REJECT OK " + friendID;
        }

        return "ANSWER_INVITATION ERROR Invalid command";
    }

    private String handleAddFriend(String content) {
        String[] parts = content.split(" ");
        int userID = Integer.parseInt(parts[0]);
        int friendID = Integer.parseInt(parts[1]);
        if (!FriendsService.addFriend(userID, friendID)) {
            return "ADD_FRIEND ERROR " + friendID;
        }

        Customer user = CustomerService.getCustomerByID(userID);
        String message = "ADD_FRIEND FROM " + Util.serializeObject(user);
        sendToUser(friendID, message);

        return "ADD_FRIEND OK " + friendID;
    }

    private String handleRegister(String user, AsynchronousSocketChannel clientChannel) {
        String[] parts = user.split(" ", 2);
        // If the text field still has class "error"
        if (parts[0].equals("ERROR")) {
            return "REGISTER ERROR Please fill in the fields below.";
        }
        List<String> fields;
        try {
            fields = Util.deserializeObject(parts[1], new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (CustomerService.getCustomerByUsername(fields.get(1)) != null) {
            return "REGISTER ERROR Username already exists";
        }
        if (CustomerService.getCustomerByEmail(fields.get(2)) != null) {
            return "REGISTER ERROR Email already exists";
        }

        Customer customer = new Customer();
        customer.setName(fields.get(0));
        customer.setUsername(fields.get(1));
        customer.setEmail(fields.get(2));

        String password = fields.get(3);
        String hashedPassword = PasswordUtil.hash(password);
        customer.setPassword(hashedPassword);

        customer.setCreateDate(new Timestamp(System.currentTimeMillis()));
        customer.setIsLock(false);
        customer.setAdmin(false);
        if (!CustomerService.addCustomer(customer)) {
            return "REGISTER ERROR Server failed to create account";
        }

        String customerContent = Util.serializeObject(customer);

        onlineUsers.put(customer.getId(), clientChannel);
        broadcast("ONLINE " + customer.getId());
        return "REGISTER " + customerContent + " END User " + customer.getUsername() + " is online now";
    }

    private String handleLogin(String user, AsynchronousSocketChannel clientChannel) {
        String[] parts = user.split(" ", 2);
        // If the text field still has class "error"
        if (parts[0].equals("ERROR")) {
            return "LOGIN ERROR Please fill in the fields below.";
        }
        List<String> fields;
        try {
            fields = Util.deserializeObject(parts[1], new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Customer customer = CustomerService.getCustomerByUsername(fields.get(0));

        if (customer == null || !PasswordUtil.verify(fields.get(1), customer.getPassword())) {
            return "LOGIN ERROR The username or password you entered is incorrect.";
        }

        if (customer.getIsLock()) {
            return "LOGIN ERROR This account is locked.";
        }

        if (onlineUsers.containsKey(customer.getId())) {
            return "LOGIN ERROR User is already online";
        }

        // Users created before the Argon2 migration still have BCrypt hashes.
        // Upgrade them transparently after a successful login.
        if (PasswordUtil.needsRehash(customer.getPassword())) {
            String upgradedHash = PasswordUtil.hash(fields.get(1));
            if (CustomerService.changePassword(customer.getId(), upgradedHash)) {
                customer.setPassword(upgradedHash);
            }
        }

        String customerContent = Util.serializeObject(customer);
        LoginTime loginTime = new LoginTime();
        loginTime.setCustomerID(customer.getId());
        loginTime.setTime(new Timestamp(System.currentTimeMillis()));
        loginTime.setIsOnline(true);

        CustomerService.addLoginCustomer(loginTime);
        onlineUsers.put(customer.getId(), clientChannel);
        broadcast("ONLINE " + customer.getId());

        return "LOGIN " + customerContent + " END User " + customer.getUsername() + " is online now"; // END is defined as the end of the data sending
    }

    private String handleForgotPassword(String message) {
        String[] parts = message.split(" ", 2);
        // If the text field still has class "error"
        if (parts[0].equals("ERROR")) {
            return "RESET_PASSWORD ERROR Please fill in the field below.";
        }
        String emailAddress = parts[1];
        Customer c = CustomerService.getCustomerByEmail(emailAddress);
        if (c == null) {
            return "RESET_PASSWORD ERROR Email does not exist";
        }
        String randomPassword = randomPassword();
        String hashedPassword = PasswordUtil.hash(randomPassword);
        c.setPassword(hashedPassword);
        if (!CustomerService.editCustomer(c)) {
            return "RESET_PASSWORD ERROR There is an error when reset password";
        }
        sendEmailResetPassword(emailAddress, randomPassword);
        return "RESET_PASSWORD Reset password successfully";
    }

    // send mail to user email to reset password
    void sendEmailResetPassword(String emailAddress, String content) {
        String emailUsername = properties.getProperty("email.username");
        String emailPassword = properties.getProperty("email.password");
        boolean auth = Boolean.parseBoolean(properties.getProperty("email.auth", "true"));

        Properties mailProps = new Properties();
        mailProps.put("mail.smtp.auth", Boolean.toString(auth));
        mailProps.put("mail.smtp.starttls.enable", properties.getProperty("email.starttls", "true"));
        mailProps.put("mail.smtp.host", properties.getProperty("email.host", "smtp.gmail.com"));
        mailProps.put("mail.smtp.port", properties.getProperty("email.port", "587"));
        Session session = Session.getInstance(mailProps, new jakarta.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(emailUsername, emailPassword);
            }
        });
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("admin@2chutieu.com", "2chutieu.com Admin"));
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(emailAddress, "user"));
            msg.setSubject("[2 chu tieu chat application][Reset Password]");
            msg.setText("Your new password is: " + content);
            Transport.send(msg);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    // random password with length = 8 and each character is (A-Z, a-z, 0-9)
    String randomPassword() {
        int leftLimit = 48; // numeral '0'
        int rightLimit = 122; // letter 'z'
        int targetStringLength = 8;
        Random random = new java.security.SecureRandom();

        return random.ints(leftLimit, rightLimit + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(targetStringLength)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private String getChatList(String id) {
        List<ChatList> chatList = MessageService.getChatList(Integer.parseInt(id));
        return "CHAT_LIST " + Util.serializeObject(chatList);
    }

    private String getOnlineUsers() {
        return "GET_ONLINE_USERS " + Util.serializeObject(onlineUsers.keySet());
    }

    private String getAllMemberConversation(String user) {
        int userID = Integer.parseInt(user);
        List<Long> conversationIDs = MessageService.getAllConversation(userID);
        Map<Long, List<Integer>> memberConversations = new HashMap<>();
        assert conversationIDs != null;
        conversationIDs.forEach(conversationID -> memberConversations.put(conversationID, MessageService.getMemberConversation(conversationID, userID)));
        return "GET_ALL_MEMBER_CONVERSATION " + Util.serializeObject(memberConversations);
    }

    private String getMessageConversation(String user) {
        String[] parts = user.split(" ", 2);
        var messageConversation = MessageService.getMessageConversation(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
        return "GET_MESSAGE_CONVERSATION " + Util.serializeObject(messageConversation);
    }

    private String updateStatusConversation(String user) {
        String[] parts = user.split(" ", 2);
        MessageService.updateStatusConversation(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
        return "UPDATE_STATUS_CONVERSATION Update status conversation successfully";
    }

    private void handleOffline(String user, AsynchronousSocketChannel clientChannel) {
        try {
            clientChannel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (user.equals("null")) {
            return;
        }
        String[] parts = user.split(" ", 4);
        int id = Integer.parseInt(parts[1]);
        onlineUsers.remove(id);
        CustomerService.updateLogoutCustomer(id, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        broadcast("OFFLINE " + id);
    }

    private String handleMessage(String content, AsynchronousSocketChannel clientChannel) {
        String[] parts = content.split(" END ", 2);
        ChatList conversation;
        MessageConversation message;
        try {
            conversation = Util.deserializeObject(parts[0], new TypeReference<>() {
            });
            message = Util.deserializeObject(parts[1], new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Integer senderID = authenticatedUser(clientChannel);
        var memberConversation = MessageService.getMemberConversation(message.conversation_id);
        if (senderID == null || memberConversation == null
                || !memberConversation.contains(senderID)
                || message.senderID == null || message.senderID.intValue() != senderID
                || conversation.conversationID != message.conversation_id) {
            return "MESSAGE ERROR Unauthorized";
        }

        // persist message to message table and message_display table
        message.id = MessageService.addMessage(message, memberConversation); // update message_id in MessageConversation
        if (message.id < 0) {
            return "MESSAGE ERROR Failed to persist message";
        }

        String sendingMessage = "MESSAGE " + Util.serializeObject(conversation) + " END " + Util.serializeObject(message);
        // send message to all members in same conversation
        memberConversation.forEach(member -> sendToUser(member, sendingMessage));
        return null;
    }

    private String removeMessageMe(String content, AsynchronousSocketChannel clientChannel) {
        Integer actorID = authenticatedUser(clientChannel);
        if (actorID == null) {
            return "REMOVE_MESSAGE_ME ERROR Unauthorized";
        }
        String[] parts = content.split(" ", 2);
        MessageService.removeMessage(Long.parseLong(parts[0]), actorID);
        return "REMOVE_MESSAGE_ME Remove message on your side successfully";
    }

    private String removeMessageAll(String content, AsynchronousSocketChannel clientChannel) {
        MessageConversation message;
        try {
            message = Util.deserializeObject(content, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Integer actorID = authenticatedUser(clientChannel);
        if (actorID == null || !isGroupMember(message.conversation_id, actorID)) {
            return "REMOVE_MESSAGE_ALL ERROR Unauthorized";
        }

        var visible = MessageService.getMessageConversation(message.conversation_id, actorID);
        boolean sentByActor = visible != null && visible.stream().anyMatch(stored ->
                stored.id == message.id
                        && stored.senderID != null
                        && stored.senderID.intValue() == actorID);
        if (!sentByActor) {
            return "REMOVE_MESSAGE_ALL ERROR Unauthorized";
        }

        var memberConversation = MessageService.getMemberConversation(message.conversation_id);
        MessageService.removeMessage(message.id);
        String removeMessage = "REMOVE_MESSAGE_ALL " + Util.serializeObject(message) + " END ";
        // send message to all members in same conversation
        if (memberConversation != null) {
            memberConversation.forEach(member -> {
                var messageConversation = MessageService.getMessageConversation(message.conversation_id, member);
                sendToUser(member, removeMessage + Util.serializeObject(messageConversation));
            });
        }
        return null;
    }

    private String removeAllMessageMe(String content, AsynchronousSocketChannel clientChannel) {
        Integer actorID = authenticatedUser(clientChannel);
        if (actorID == null) {
            return "REMOVE_ALL_MESSAGE_ME ERROR Unauthorized";
        }
        String[] parts = content.split(" END ", 2);
        long conversationID = Long.parseLong(parts[0]);
        if (!isGroupMember(conversationID, actorID)) {
            return "REMOVE_ALL_MESSAGE_ME ERROR Unauthorized";
        }
        try {
            MessageService.removeAllMessage(conversationID, actorID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "REMOVE_ALL_MESSAGE_ME Remove all message on your side successfully";
    }

    private String updateAccount(String user, AsynchronousSocketChannel clientChannel) {
        Customer c;
        try {
            c = Util.deserializeObject(user, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Integer actorID = authenticatedUser(clientChannel);
        if (actorID == null || c.getId() != actorID) {
            return "UPDATE_ACCOUNT ERROR Unauthorized";
        }
        Customer byUsername = CustomerService.getCustomerByUsername(c.getUsername());
        if (byUsername != null && byUsername.getId() != c.getId()) {
            return "UPDATE_ACCOUNT ERROR Username already exists";
        }
        Customer byEmail = CustomerService.getCustomerByEmail(c.getEmail());
        if (byEmail != null && byEmail.getId() != c.getId()) {
            return "UPDATE_ACCOUNT ERROR Email already exists";
        }

        if (c.getPassword() != null) {
            String password = c.getPassword();
            String hashedPassword = PasswordUtil.hash(password);
            c.setPassword(hashedPassword);
        }

        if (CustomerService.updateCustomer(c)) {
            return "UPDATE_ACCOUNT " + Util.serializeObject(c) + " END " + "Update account successfully";
        } else {
            return "UPDATE_ACCOUNT ERROR There is error in update account";
        }
    }

    private String handleGetFriendList(String content) {
        String[] parts = content.split(" ", 2);
        int id = Integer.parseInt(parts[1]);

        List<Customer> friends;
        friends = FriendsService.fetchFriends(id);

        if (friends == null) {
            return "GET_FRIEND_LIST " + parts[0] + " ERROR Failed to fetch friends";
        }

        return "GET_FRIEND_LIST " + parts[0] + " OK" + (parts[0].equals("ADMIN") ? " " + id : "") + " " + Util.serializeObject(friends);
    }

    private String handleUnfriend(String message) {
        String[] parts = message.split(" ");
        int userID = Integer.parseInt(parts[0]);
        int friendID = Integer.parseInt(parts[1]);
        if (!FriendsService.removeFriend(userID, friendID)) {
            return "UNFRIEND ERROR " + friendID;
        }

        AsynchronousSocketChannel friendChannel = onlineUsers.get(friendID);
        if (friendChannel != null) {
            try {
                writeResponse(friendChannel, "UNFRIEND FROM " + userID);
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return "UNFRIEND OK " + friendID;
    }

    private String handleSearchUser(String content) {
        String[] parts = content.split(" ", 2);
        int username = Integer.parseInt(parts[0]);
        String prompt = parts[1];
        List<Customer> users = FriendsService.fetchUsers(username, prompt);
        if (users == null) {
            return "SEARCH_USER ERROR Failed to search user";
        }
        return "SEARCH_USER OK " + Util.serializeObject(users);
    }

    private String handleSpamReport(String content) {
        String[] parts = content.split(" ");
        int userID = Integer.parseInt(parts[0]);
        int reportedID = Integer.parseInt(parts[1]);
        if (!FriendsService.reportSpam(userID, reportedID)) {
            return "SPAM ERROR " + reportedID;
        }
        return "SPAM OK " + reportedID;
    }

    private String handleBlock(String message) {
        String[] parts = message.split(" ");
        int userID = Integer.parseInt(parts[0]);
        int blockedID = Integer.parseInt(parts[1]);
        if (!FriendsService.blockUser(userID, blockedID)) {
            return "BLOCK ERROR " + blockedID;
        }

        AsynchronousSocketChannel blockedChannel = onlineUsers.get(blockedID);
        if (blockedChannel != null) {
            try {
                writeResponse(blockedChannel, "BLOCKED FROM " + userID);
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        return "BLOCK OK " + blockedID;
    }

    private String handleAdminLogin(String content, AsynchronousSocketChannel clientChannel) {
        String[] parts = content.split(" ");
        String username = parts[0];
        String password = parts[1];

        Customer account = AdminService.getAdminAccount(username);

        if (account != null && PasswordUtil.verify(password, account.getPassword())) {
            if (PasswordUtil.needsRehash(account.getPassword())) {
                CustomerService.changePassword(account.getId(), PasswordUtil.hash(password));
            }
            adminChannels.add(clientChannel);
            return "LOGIN_ADMIN OK null";
        }

        return "LOGIN_ADMIN INCORRECT Username or password is incorrect";
    }

    private String handleFetchAccountList() {
        List<Customer> allAccounts = CustomerService.fetchAllCustomers();
        return "FETCH_ACCOUNT_LIST OK " + Util.serializeObject(allAccounts);
    }

    private String handleFetchGroupList() {
        List<Conversation> allGroups = AdminService.getConversation();
        return "FETCH_GROUP_LIST " + Util.serializeObject(allGroups);
    }

    private String handleFetchMemberList(String content) {
        List<MemberConversation> allMembers = AdminService.getMemberConversation(Long.parseLong(content));
        return "FETCH_MEMBER_LIST " + Util.serializeObject(allMembers);
    }

    private String handleAddAccount(String content) {
        Customer cus;
        cus = Util.deserializeObject(content, new TypeReference<>() {
        });

        if (CustomerService.getCustomerByUsername(cus.getUsername()) != null) {
            return "ADD_ACCOUNT EXISTS Username already exists!";
        }

        if (CustomerService.getCustomerByEmail(cus.getEmail()) != null) {
            return "ADD_ACCOUNT EXISTS Email already exists!";
        }

        if (!CustomerService.addCustomer(cus)) {
            return "ADD_ACCOUNT ERROR Server failed to add account";
        }

        Customer newAccount = CustomerService.getCustomerByUsername(cus.getUsername());
        return "ADD_ACCOUNT OK " + Util.serializeObject(newAccount);
    }

    private String handleDeleteAccount(String content) {
        int id = Integer.parseInt(content);
        if (!CustomerService.deleteCustomer(id)) {
            return "DELETE_ACCOUNT ERROR " + id;
        }
        return "DELETE_ACCOUNT OK " + id;
    }

    private String handleEditAccount(String content) {
        Customer cus;
        cus = Util.deserializeObject(content, new TypeReference<>() {
        });

        Customer checkCus = CustomerService.getCustomerByUsername(cus.getUsername());
        if (checkCus != null && checkCus.getId() != cus.getId()) {
            return "EDIT_ACCOUNT ERROR Username already exists!";
        }

        checkCus = CustomerService.getCustomerByEmail(cus.getEmail());
        if (checkCus != null && checkCus.getId() != cus.getId()) {
            return "EDIT_ACCOUNT ERROR Email already exists!";
        }

        if (!CustomerService.editCustomer(cus)) {
            return "EDIT_ACCOUNT ERROR Server failed to edit account";
        }

        return "EDIT_ACCOUNT OK " + content;
    }

    private String handleLoginHistory(String content) {
        if (content.startsWith("ALL")) {
            int rows = Integer.parseInt(content.split(" ", 2)[1]);
            List<UserLoginTime> loginTimes = HistoryService.fetchAllLoginHistory(rows);
            if (loginTimes == null) {
                return "LOGIN_HISTORY ALL ERROR";
            }
            return "LOGIN_HISTORY ALL OK " + Util.serializeObject(loginTimes);
        } else if (content.startsWith("USER")) {
            int id = Integer.parseInt(content.split(" ")[1]);
            List<LoginTime> loginTimes = HistoryService.fetchUserLoginHistory(id);
            if (loginTimes == null) {
                return "LOGIN_HISTORY USER ERROR";
            }
            return "LOGIN_HISTORY USER OK " + Util.serializeObject(loginTimes);
        }
        return "LOGIN_HISTORY ERROR Incorrect command";
    }

    private String handleToggleLockAccount(String content) {
        int id = Integer.parseInt(content);
        if (!CustomerService.toggleLockStatusAccount(id)) {
            return "TOGGLE_ACCOUNT_STATUS ERROR " + id;
        }
        return "TOGGLE_ACCOUNT_STATUS OK " + id;
    }

    private String handleChangePassword(String content) {
        String[] parts = content.split(" ", 2);
        int id = Integer.parseInt(parts[0]);
        String password = parts[1];

        if (!CustomerService.changePassword(id, password)) {
            return "CHANGE_PASSWORD ERROR " + id;
        }
        return "CHANGE_PASSWORD OK " + id;
    }

    private String handleSpamList(String content) {
        if (content.startsWith("ALL")) {
            List<SpamList> spamList = AdminService.fetchAllSpamList();
            if (spamList == null) {
                return "SPAM_LIST ERROR";
            }
            return "SPAM_LIST OK " + Util.serializeObject(spamList);
        }

        String[] parts = content.split(" END ", 2);
        Timestamp fromDate = Timestamp.valueOf(parts[0]);
        Timestamp toDate = Timestamp.valueOf(parts[1]);
        List<SpamList> spamList = AdminService.fetchSpamList(fromDate, toDate);
        if (spamList == null) {
            return "SPAM_LIST ERROR";
        }
        return "SPAM_LIST OK " + Util.serializeObject(spamList);
    }

    private String handleLockAccount(String id) {
        int userID = Integer.parseInt(id);
        if (!CustomerService.toggleLockStatusAccount(userID)) {
            return "LOCK_ACCOUNT ERROR " + userID;
        }
        return "LOCK_ACCOUNT OK " + userID;
    }

    private String handleFriendCount() {
        List<FriendCount> friendCountList = AdminService.fetchFriendCountList();
        if (friendCountList == null) {
            return "FRIEND_COUNT ERROR null";
        }
        return "FRIEND_COUNT OK " + Util.serializeObject(friendCountList);
    }

    private String handleOnlineUserCountList() {
        List<OnlineUserCount> onlineUserCountList = AdminService.getOnlineUserCountList();
        return "FETCH_ONLINE_USER_COUNT_LIST " + Util.serializeObject(onlineUserCountList);
    }

    private String handleOnlineUserCountTimeRangeList(String content) {
        String[] parts = content.split(" END ", 2);
        Timestamp fromDate = Timestamp.valueOf(parts[0]);
        Timestamp toDate = Timestamp.valueOf(parts[1]);
        List<OnlineUserCount> onlineUserCountList = AdminService.getOnlineUserCountList(fromDate, toDate);
        return "FETCH_ONLINE_USER_COUNT_LIST " + Util.serializeObject(onlineUserCountList);
    }

    private String handleNewUsersMonthly() {
        Map<Integer, List<Long>> newUsers = AdminService.getNewUsersMonthly();
        return "FETCH_NEW_USERS_MONTHLY " + Util.serializeObject(newUsers);
    }

    private String handleAppUsageMonthly() {
        Map<Integer, List<Long>> appUsage = AdminService.getAppUsageMonthly();
        return "FETCH_APP_USAGE_MONTHLY " + Util.serializeObject(appUsage);
    }

    private String handleGetFriendRequest(String content) {
        int id = Integer.parseInt(content);
        List<Customer> friendRequests = FriendsService.fetchFriendRequests(id);
        if (friendRequests == null) {
            return "GET_FRIEND_REQUEST ERROR Failed to fetch friend requests";
        }
        return "GET_FRIEND_REQUEST OK " + Util.serializeObject(friendRequests);
    }

    private String handleCreateGroup(String content) {
        String[] parts = content.split(" ", 2);
        int userID = Integer.parseInt(parts[0]);

        String[] parts2 = parts[1].split("END");
        String groupName = parts2[0].trim();
        String members = parts2[1].trim();

        List<Integer> memberIDs = Util.deserializeObject(members, new TypeReference<>() {
        });
        Conversation conversation = new Conversation();
        conversation.setName(groupName);
        conversation.setIsGroup(true);
        conversation.setCreateDate(new Timestamp(System.currentTimeMillis()));

        long conversationId = MessageService.createGroupConversation(userID, conversation, memberIDs);
        if (conversationId == -1) {
            return "CREATE_GROUP ERROR Failed to create group";
        }

        ChatList chatList = new ChatList();
        chatList.conversationID = conversationId;
        chatList.conversationName = groupName;
        chatList.isGroup = true;

        return "CREATE_GROUP OK " + Util.serializeObject(chatList);
    }

    private String handleGroup(String message, AsynchronousSocketChannel clientChannel) {
        String[] parts = message.split(" ", 3);
        long conversationID = Long.parseLong(parts[1]);
        String content = parts[2];
        Integer actorID = authenticatedUser(clientChannel);

        if (actorID == null || !isGroupMember(conversationID, actorID)) {
            return "GROUP ERROR Unauthorized";
        }
        if (!parts[0].equals("GET_MEMBERS") && !isGroupAdmin(conversationID, actorID)) {
            return "GROUP ERROR Unauthorized";
        }

        switch (parts[0]) {
            case "ADD_MEMBER":
                if (MessageService.addMembersToGroupConversation(conversationID, Util.deserializeObject(content, new TypeReference<>() {
                }))) {
                    List<MemberConversation> users = MessageService.getMemberConversationFullInfo(conversationID);
                    if (users != null) {
                        // Send the message to other members in the group
                        String sendingMessage = "GROUP ADD_MEMBER OK " + conversationID + " " + Util.serializeObject(users);
                        users.forEach(user -> sendToUser(user.getId(), sendingMessage));
                        return sendingMessage;
                    }
                }
                return "GROUP ADD_MEMBER ERROR " + conversationID;

            case "REMOVE_MEMBER":
                int memberID = Integer.parseInt(content);
                if (MessageService.removeMemberFromGroupConversation(conversationID, memberID)) {
                    List<MemberConversation> users = MessageService.getMemberConversationFullInfo(conversationID);
                    if (users != null) {
                        // Send the message to other members in the group
                        sendToUser(memberID, "GROUP REMOVE_MEMBER FROM " + conversationID + " null");
                        String sendingMessage = "GROUP REMOVE_MEMBER OK " + conversationID + " " + Util.serializeObject(users);
                        users.forEach(user -> sendToUser(user.getId(), sendingMessage));
                        return sendingMessage;
                    }
                }
                return "GROUP REMOVE_MEMBER ERROR " + conversationID;

            case "GET_MEMBERS":
                List<MemberConversation> users = MessageService.getMemberConversationFullInfo(conversationID);
                if (users == null) {
                    return "GROUP GET_MEMBERS ERROR " + conversationID;
                }
                return "GROUP GET_MEMBERS OK " + conversationID + " " + Util.serializeObject(users);

            case "UPDATE_NAME":
                if (MessageService.updateGroupName(conversationID, content)) {
                    // Send the message to other members in the group
                    String sendingMessage = "GROUP UPDATE_NAME OK " + conversationID + " " + content;
                    List<MemberConversation> members = MessageService.getMemberConversationFullInfo(conversationID);
                    assert members != null;
                    members.forEach(user -> sendToUser(user.getId(), sendingMessage));
                    return sendingMessage;
                }
                return "GROUP UPDATE_NAME ERROR " + conversationID;

            case "ASSIGN_ADMIN":
                int userId = Integer.parseInt(content.split(" ")[0]);
                boolean isAdmin = Boolean.parseBoolean(content.split(" ")[1]);
                if (MessageService.assignGroupAdmin(conversationID, userId, isAdmin)) {
                    // Send the message to other members in the group
                    List<MemberConversation> members = MessageService.getMemberConversationFullInfo(conversationID);
                    String sendingMessage = "GROUP ASSIGN_ADMIN OK " + conversationID + " " + Util.serializeObject(members);
                    assert members != null;
                    members.forEach(user -> sendToUser(user.getId(), sendingMessage));
                    return sendingMessage;
                }
                return "GROUP ASSIGN_ADMIN ERROR " + conversationID;
        }

        return "GROUP ERROR Invalid command";
    }

    private Integer authenticatedUser(AsynchronousSocketChannel clientChannel) {
        return onlineUsers.entrySet().stream()
                .filter(entry -> entry.getValue() == clientChannel)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private boolean isGroupMember(long conversationID, int userID) {
        List<Integer> members = MessageService.getMemberConversation(conversationID);
        return members != null && members.contains(userID);
    }

    private boolean isGroupAdmin(long conversationID, int userID) {
        List<MemberConversation> members = MessageService.getMemberConversationFullInfo(conversationID);
        return members != null && members.stream()
                .anyMatch(member -> member.getId() == userID && Boolean.TRUE.equals(member.getIsAdmin()));
    }

    private String handleOpenChat(String content) {
        String[] parts = content.split(" ", 2);
        int userID = Integer.parseInt(parts[0]);
        int friendID = Integer.parseInt(parts[1]);

        long conversationID = MessageService.getSingleConversation(userID, friendID);
        // Conversation not exist, create one
        if (conversationID == -1) {
            conversationID = MessageService.createSingleConversation(userID, friendID);
        }

        if (conversationID == -1) {
            return "OPEN_CHAT ERROR Failed to create new conversation";
        }

        ChatList chatList = new ChatList();
        chatList.conversationID = conversationID;
        chatList.conversationName = Objects.requireNonNull(CustomerService.getCustomerByID(friendID)).getName();
        chatList.isGroup = false;
        chatList.latestTime = new Timestamp(System.currentTimeMillis());

        return "OPEN_CHAT OK " + conversationID + " " + Util.serializeObject(chatList);
    }

    private void writeResponse(AsynchronousSocketChannel channel, String response) throws ExecutionException, InterruptedException {
        byte[] bytes = (response + "\n").getBytes(StandardCharsets.UTF_8);
        // AsynchronousSocketChannel allows one pending write. Serialize complete
        // protocol lines so concurrent broadcasts cannot interleave or be dropped.
        synchronized (channel) {
            for (int offset = 0; offset < bytes.length; offset += bufferSize) {
                int length = Math.min(bufferSize, bytes.length - offset);
                channel.write(ByteBuffer.wrap(bytes, offset, length)).get();
            }
        }
    }

    private void sendToUser(int userID, String message) {
        AsynchronousSocketChannel channel = onlineUsers.get(userID);
        if (channel == null) {
            return;
        }
        try {
            writeResponse(channel, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // A dead client must not abort a broadcast to everyone else.
            onlineUsers.remove(userID, channel);
        }
    }

    private void broadcast(String message) {
        onlineUsers.keySet().forEach(userID -> sendToUser(userID, message));
    }

    private String handleDeleteSpam(String content) {
        String[] parts = content.split(" ", 2);
        int customerId = Integer.parseInt(parts[0]);
        int spamId = Integer.parseInt(parts[1]);

        if (!AdminService.deleteSpam(customerId, spamId)) {
            return "DELETE_SPAM ERROR " + customerId + " " + spamId;
        }
        return "DELETE_SPAM OK " + customerId + " " + spamId;
    }

    private String handleFetchNewAccount(String content) {
        int rows = Integer.parseInt(content);
        List<Customer> newAccounts = AdminService.fetchNewAccounts(rows);
        if (newAccounts == null) {
            return "FETCH_NEW_ACCOUNTS ERROR Failed to fetch new accounts";
        }
        return "FETCH_NEW_ACCOUNTS OK " + Util.serializeObject(newAccounts);
    }

    private static class SocketServerHelper {
        private static final SocketServer INSTANCE = new SocketServer();
    }
}
