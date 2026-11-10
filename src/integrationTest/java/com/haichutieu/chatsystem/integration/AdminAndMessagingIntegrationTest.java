package com.haichutieu.chatsystem.integration;

import com.haichutieu.chatsystem.dto.ChatList;
import com.haichutieu.chatsystem.dto.Conversation;
import com.haichutieu.chatsystem.dto.Customer;
import com.haichutieu.chatsystem.dto.LoginTime;
import com.haichutieu.chatsystem.dto.MemberConversation;
import com.haichutieu.chatsystem.dto.MessageConversation;
import com.haichutieu.chatsystem.dto.SpamList;
import com.haichutieu.chatsystem.server.dal.AdminService;
import com.haichutieu.chatsystem.server.dal.CustomerService;
import com.haichutieu.chatsystem.server.dal.FriendsService;
import com.haichutieu.chatsystem.server.dal.HistoryService;
import com.haichutieu.chatsystem.server.dal.MessageService;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAndMessagingIntegrationTest extends PostgresTestBase {

    @Test
    void shouldReportAndDeleteSpamWithAdminProjection() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");

        assertThat(FriendsService.reportSpam(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.reportSpam(alice.getId(), bob.getId())).isFalse();

        List<SpamList> spam = AdminService.fetchAllSpamList();
        assertThat(spam).hasSize(1);
        SpamList entry = spam.getFirst();
        assertThat(entry.getCustomerID()).isEqualTo(alice.getId());
        assertThat(entry.getPersonID()).isEqualTo(bob.getId());
        assertThat(entry.getUsername()).isEqualTo("bob");
        assertThat(entry.getUserReported()).isEqualTo("alice");

        Timestamp from = new Timestamp(System.currentTimeMillis() - 60_000);
        Timestamp to = new Timestamp(System.currentTimeMillis() + 60_000);
        assertThat(AdminService.fetchSpamList(from, to)).hasSize(1);

        assertThat(AdminService.deleteSpam(alice.getId(), bob.getId())).isTrue();
        assertThat(AdminService.fetchAllSpamList()).isEmpty();
    }

    @Test
    void shouldSearchBlockAndCompleteFriendLifecycle() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        Customer charlie = createCustomer("charlie");

        assertThat(FriendsService.fetchUsers(alice.getId(), "bo"))
                .extracting(Customer::getUsername)
                .containsExactly("bob");

        assertThat(FriendsService.addFriend(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.addFriend(alice.getId(), bob.getId())).isFalse();
        assertThat(FriendsService.fetchFriendRequests(bob.getId()))
                .extracting(Customer::getUsername)
                .containsExactly("alice");

        assertThat(FriendsService.acceptFriend(bob.getId(), alice.getId())).isTrue();
        assertThat(FriendsService.acceptFriend(bob.getId(), alice.getId())).isFalse();
        assertThat(FriendsService.fetchFriends(alice.getId()))
                .extracting(Customer::getUsername)
                .containsExactly("bob");

        assertThat(FriendsService.addFriend(charlie.getId(), alice.getId())).isTrue();
        assertThat(FriendsService.rejectFriend(alice.getId(), charlie.getId())).isTrue();
        assertThat(FriendsService.rejectFriend(alice.getId(), charlie.getId())).isFalse();
        assertThat(FriendsService.fetchFriendRequests(alice.getId())).isEmpty();

        assertThat(FriendsService.removeFriend(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.removeFriend(alice.getId(), bob.getId())).isFalse();
        assertThat(FriendsService.fetchFriends(alice.getId())).isEmpty();

        assertThat(FriendsService.blockUser(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.blockUser(alice.getId(), bob.getId())).isFalse();
        assertThat(FriendsService.fetchUsers(alice.getId(), "bob")).isEmpty();
        assertThat(FriendsService.fetchUsers(bob.getId(), "alice")).isEmpty();
    }

    @Test
    void shouldTrackReadStatusAndRemoveMessagesPerUser() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        long first = MessageService.addMessage(
                new MessageConversation(conversationId, alice.getId(), "Alice", now, "first"),
                List.of(alice.getId(), bob.getId()));
        long second = MessageService.addMessage(
                new MessageConversation(conversationId, alice.getId(), "Alice",
                        new Timestamp(now.getTime() + 1), "second"),
                List.of(alice.getId(), bob.getId()));

        ChatList beforeRead = MessageService.getChatList(bob.getId()).getFirst();
        assertThat(beforeRead.latestMessage).isEqualTo("second");
        assertThat(beforeRead.isSeen).isFalse();

        MessageService.updateStatusConversation(conversationId, bob.getId());
        ChatList afterRead = MessageService.getChatList(bob.getId()).getFirst();
        assertThat(afterRead.isSeen).isTrue();

        MessageService.removeAllMessage(conversationId, bob.getId());
        assertThat(MessageService.getMessageConversation(conversationId, bob.getId())).isEmpty();
        assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).hasSize(2);
        assertThat(MessageService.getChatList(bob.getId())).isEmpty();

        MessageService.removeMessage(first);
        MessageService.removeMessage(second);
        assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).isEmpty();
    }

    @Test
    void shouldCreateGroupAndManageMembersWithoutDuplicateRows() {
        Customer owner = createCustomer("owner");
        Customer member = createCustomer("member");
        Customer outsider = createCustomer("outsider");
        Conversation group = group("Team");

        long conversationId = MessageService.createGroupConversation(owner.getId(), group,
                List.of(member.getId(), member.getId(), owner.getId()));

        assertThat(conversationId).isPositive();
        assertThat(MessageService.getMemberConversationFullInfo(conversationId)).hasSize(2);
        assertThat(MessageService.assignGroupAdmin(conversationId, member.getId(), true)).isTrue();
        assertThat(MessageService.updateGroupName(conversationId, "Renamed")).isTrue();
        assertThat(AdminService.getConversation())
                .filteredOn(c -> c.getId() == conversationId)
                .extracting(Conversation::getName)
                .containsExactly("Renamed");

        assertThat(MessageService.addMembersToGroupConversation(conversationId,
                List.of(outsider.getId(), outsider.getId()))).isTrue();
        assertThat(MessageService.getMemberConversation(conversationId)).hasSize(3);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        MessageService.addMessage(new MessageConversation(conversationId, owner.getId(), "Owner", now, "hello"),
                List.of(owner.getId(), member.getId(), outsider.getId()));

        assertThat(MessageService.removeMemberFromGroupConversation(conversationId, outsider.getId())).isTrue();
        assertThat(MessageService.removeMemberFromGroupConversation(conversationId, outsider.getId())).isFalse();
        assertThat(MessageService.assignGroupAdmin(conversationId, outsider.getId(), true)).isFalse();
        assertThat(MessageService.getMemberConversation(conversationId))
                .containsExactlyInAnyOrder(owner.getId(), member.getId());
        assertThat(MessageService.getMessageConversation(conversationId, outsider.getId())).isEmpty();
    }

    @Test
    void shouldExposeAdminListsAndMonthlyStatistics() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        Customer admin = createAdmin("adminuser");

        assertThat(AdminService.getAdminAccount("adminuser").getId()).isEqualTo(admin.getId());
        assertThat(AdminService.getAdminAccount("alice")).isNull();
        assertThat(CustomerService.fetchAllCustomers()).hasSize(3);
        assertThat(AdminService.fetchNewAccounts(2)).hasSize(2);

        assertThat(FriendsService.addFriend(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.acceptFriend(bob.getId(), alice.getId())).isTrue();
        assertThat(AdminService.fetchFriendCountList())
                .filteredOn(count -> count.getId() == alice.getId())
                .extracting(count -> count.getFriendCount())
                .containsExactly(1L);

        LoginTime login = new LoginTime();
        login.setCustomerID(alice.getId());
        login.setTime(new Timestamp(System.currentTimeMillis()));
        login.setIsOnline(true);
        CustomerService.addLoginCustomer(login);
        CustomerService.updateLogoutCustomer(alice.getId(), 1, 0);

        assertThat(HistoryService.fetchAllLoginHistory(10)).hasSize(1);
        assertThat(AdminService.getOnlineUserCountList()).hasSize(1);
        assertThat(AdminService.getOnlineUserCountList(
                new Timestamp(System.currentTimeMillis() - 60_000),
                new Timestamp(System.currentTimeMillis() + 60_000))).hasSize(1);
        assertThat(AdminService.getNewUsersMonthly()).containsKey(currentYear());
        assertThat(AdminService.getAppUsageMonthly()).containsKey(currentYear());
        assertThat(AdminService.getNewUsersMonthly().get(currentYear())).hasSize(12);
        assertThat(AdminService.getAppUsageMonthly().get(currentYear())).hasSize(12);
    }

    @Test
    void shouldRollBackAFailedMessageAndKeepTheDatabaseUsable() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());

        long failed = MessageService.addMessage(
                new MessageConversation(conversationId, alice.getId(), "Alice",
                        new Timestamp(System.currentTimeMillis()), null),
                List.of(alice.getId(), bob.getId()));

        assertThat(failed).isEqualTo(-1L);
        assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).isEmpty();

        long stored = MessageService.addMessage(
                new MessageConversation(conversationId, alice.getId(), "Alice",
                        new Timestamp(System.currentTimeMillis()), "after rollback"),
                List.of(alice.getId(), bob.getId()));
        assertThat(stored).isPositive();
        assertThat(MessageService.getMessageConversation(conversationId, bob.getId()))
                .extracting(message -> message.message)
                .containsExactly("after rollback");
    }

    @Test
    void shouldAcceptOnlyOneOfTwoConcurrentDuplicateFriendRequests() throws Exception {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = pool.submit(() -> FriendsService.addFriend(alice.getId(), bob.getId()));
            Future<Boolean> second = pool.submit(() -> FriendsService.addFriend(alice.getId(), bob.getId()));
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }

        assertThat(FriendsService.fetchFriendRequests(bob.getId())).hasSize(1);
        assertThat(FriendsService.fetchFriends(alice.getId())).isEmpty();
    }

    private static Conversation group(String name) {
        Conversation group = new Conversation();
        group.setName(name);
        group.setIsGroup(true);
        group.setCreateDate(new Timestamp(System.currentTimeMillis()));
        return group;
    }

    private static int currentYear() {
        return java.time.Year.now().getValue();
    }
}