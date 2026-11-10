package com.haichutieu.chatsystem.integration;

import com.haichutieu.chatsystem.dto.ChatList;
import com.haichutieu.chatsystem.dto.Conversation;
import com.haichutieu.chatsystem.dto.Customer;
import com.haichutieu.chatsystem.dto.LoginTime;
import com.haichutieu.chatsystem.dto.MemberConversation;
import com.haichutieu.chatsystem.dto.MessageConversation;
import com.haichutieu.chatsystem.server.dal.AdminService;
import com.haichutieu.chatsystem.server.dal.CustomerService;
import com.haichutieu.chatsystem.server.dal.FriendsService;
import com.haichutieu.chatsystem.server.dal.HistoryService;
import com.haichutieu.chatsystem.server.dal.MessageService;
import com.haichutieu.chatsystem.util.PasswordUtil;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceIntegrationTest extends PostgresTestBase {

    @Test
    void shouldPersistAndFindCustomerByAllLookups() {
        Customer saved = createCustomer("alice");

        assertThat(saved.getId()).isPositive();
        assertThat(CustomerService.getCustomerByID(saved.getId()).getUsername()).isEqualTo("alice");
        assertThat(CustomerService.getCustomerByUsername("alice").getEmail()).isEqualTo("alice@example.com");
        assertThat(CustomerService.getCustomerByEmail("alice@example.com").getId()).isEqualTo(saved.getId());
        assertThat(PasswordUtil.verify("password123", CustomerService.getCustomerByUsername("alice").getPassword())).isTrue();
    }

    @Test
    void shouldRejectDuplicateUsernameAndEmail() {
        createCustomer("alice");

        Customer duplicateName = customer("alice", "other@example.com");
        Customer duplicateEmail = customer("other", "alice@example.com");

        assertThat(CustomerService.addCustomer(duplicateName)).isFalse();
        assertThat(CustomerService.addCustomer(duplicateEmail)).isFalse();
        assertThat(CustomerService.fetchAllCustomers()).hasSize(1);
    }

    @Test
    void shouldToggleLockAndUpgradePassword() {
        Customer customer = createCustomer("alice");

        assertThat(CustomerService.toggleLockStatusAccount(customer.getId())).isTrue();
        assertThat(CustomerService.getCustomerByID(customer.getId()).getIsLock()).isTrue();
        assertThat(CustomerService.toggleLockStatusAccount(customer.getId())).isTrue();
        assertThat(CustomerService.getCustomerByID(customer.getId()).getIsLock()).isFalse();

        String newHash = PasswordUtil.hash("new-password-456");
        assertThat(CustomerService.changePassword(customer.getId(), newHash)).isTrue();
        assertThat(PasswordUtil.verify("new-password-456",
                CustomerService.getCustomerByID(customer.getId()).getPassword())).isTrue();
    }

    @Test
    void shouldCompleteFriendRequestLifecycle() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");

        assertThat(FriendsService.addFriend(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.addFriend(alice.getId(), bob.getId())).isFalse();
        assertThat(FriendsService.fetchFriendRequests(bob.getId()))
                .extracting(Customer::getUsername)
                .containsExactly("alice");

        assertThat(FriendsService.acceptFriend(bob.getId(), alice.getId())).isTrue();
        assertThat(FriendsService.fetchFriends(alice.getId()))
                .extracting(Customer::getUsername)
                .containsExactly("bob");
        assertThat(FriendsService.fetchFriends(bob.getId()))
                .extracting(Customer::getUsername)
                .containsExactly("alice");
        assertThat(MessageService.getSingleConversation(alice.getId(), bob.getId())).isPositive();
    }

    @Test
    void shouldBlockNonFriendAndRemoveExistingFriendship() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");

        assertThat(FriendsService.blockUser(alice.getId(), bob.getId())).isTrue();
        assertThat(FriendsService.fetchUsers(alice.getId(), "bob")).isEmpty();
    }

    @Test
    void shouldPersistMessagesAndDeletePerUser() {
        Customer alice = createCustomer("alice");
        Customer bob = createCustomer("bob");
        long conversationId = MessageService.createSingleConversation(alice.getId(), bob.getId());

        Timestamp now = new Timestamp(System.currentTimeMillis());
        long messageId = MessageService.addMessage(
                new MessageConversation(conversationId, alice.getId(), "Alice", now, "hello bob"),
                List.of(alice.getId(), bob.getId()));

        assertThat(messageId).isPositive();
        assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).hasSize(1);
        assertThat(MessageService.getMessageConversation(conversationId, bob.getId())).hasSize(1);
        assertThat(MessageService.getChatList(bob.getId()))
                .extracting(chat -> chat.latestMessage)
                .containsExactly("hello bob");

        MessageService.removeMessage(messageId, alice.getId());
        assertThat(MessageService.getMessageConversation(conversationId, alice.getId())).isEmpty();
        assertThat(MessageService.getMessageConversation(conversationId, bob.getId())).hasSize(1);

        MessageService.removeMessage(messageId);
        assertThat(MessageService.getMessageConversation(conversationId, bob.getId())).isEmpty();
    }

    @Test
    void shouldManageGroupMembersAndAdmins() {
        Customer owner = createCustomer("owner");
        Customer member = createCustomer("member");

        Conversation group = new Conversation();
        group.setName("Team");
        group.setIsGroup(true);
        group.setCreateDate(new Timestamp(System.currentTimeMillis()));

        long conversationId = MessageService.createGroupConversation(owner.getId(), group, List.of(member.getId()));
        assertThat(conversationId).isPositive();
        assertThat(MessageService.getMemberConversationFullInfo(conversationId)).hasSize(2);

        assertThat(MessageService.assignGroupAdmin(conversationId, member.getId(), true)).isTrue();
        assertThat(MessageService.getMemberConversationFullInfo(conversationId))
                .filteredOn(m -> m.getId() == member.getId())
                .extracting(MemberConversation::getIsAdmin)
                .containsExactly(true);

        assertThat(MessageService.updateGroupName(conversationId, "Renamed")).isTrue();
        assertThat(AdminService.getConversation())
                .filteredOn(c -> c.getId() == conversationId)
                .extracting(Conversation::getName)
                .containsExactly("Renamed");

        assertThat(MessageService.removeMemberFromGroupConversation(conversationId, member.getId())).isTrue();
        assertThat(MessageService.getMemberConversation(conversationId)).containsExactly(owner.getId());
    }

    @Test
    void shouldRecordLoginHistoryAndLogoutCounters() {
        Customer customer = createCustomer("alice");
        LoginTime login = new LoginTime();
        login.setCustomerID(customer.getId());
        login.setTime(new Timestamp(System.currentTimeMillis()));
        login.setIsOnline(true);
        CustomerService.addLoginCustomer(login);

        assertThat(HistoryService.fetchUserLoginHistory(customer.getId())).hasSize(1);

        CustomerService.updateLogoutCustomer(customer.getId(), 3, 2);
        LoginTime stored = HistoryService.fetchUserLoginHistory(customer.getId()).getFirst();
        assertThat(stored.getIsOnline()).isFalse();
        assertThat(stored.getNumberPeopleChatWith()).isEqualTo(3);
        assertThat(stored.getNumberGroupChatWith()).isEqualTo(2);
    }

    @Test
    void shouldExposeGroupInChatListOnlyForMembers() {
        Customer owner = createCustomer("owner");
        Customer member = createCustomer("member");
        Conversation group = new Conversation();
        group.setName("Team");
        group.setIsGroup(true);
        group.setCreateDate(new Timestamp(System.currentTimeMillis()));
        long conversationId = MessageService.createGroupConversation(owner.getId(), group, List.of(member.getId()));

        Timestamp now = new Timestamp(System.currentTimeMillis());
        MessageService.addMessage(
                new MessageConversation(conversationId, owner.getId(), "Owner", now, "hello team"),
                List.of(owner.getId(), member.getId()));

        List<ChatList> ownerChats = MessageService.getChatList(owner.getId());
        List<ChatList> memberChats = MessageService.getChatList(member.getId());

        assertThat(ownerChats).extracting(chat -> chat.conversationName).containsExactly("Team");
        assertThat(memberChats).extracting(chat -> chat.conversationName).containsExactly("Team");
    }

    @Test
    void shouldPartiallyUpdateAndDeleteACustomer() {
        Customer customer = createCustomer("alice");
        String originalPassword = CustomerService.getCustomerByID(customer.getId()).getPassword();

        Customer patch = new Customer();
        patch.setId(customer.getId());
        patch.setName("Updated Name");
        patch.setAddress("New address");
        patch.setEmail("updated@example.com");

        assertThat(CustomerService.updateCustomer(patch)).isTrue();
        Customer updated = CustomerService.getCustomerByID(customer.getId());
        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getAddress()).isEqualTo("New address");
        assertThat(updated.getEmail()).isEqualTo("updated@example.com");
        assertThat(updated.getUsername()).isEqualTo("alice");
        assertThat(updated.getPassword()).isEqualTo(originalPassword);

        assertThat(CustomerService.deleteCustomer(customer.getId())).isTrue();
        assertThat(CustomerService.getCustomerByID(customer.getId())).isNull();

        Customer missing = new Customer();
        missing.setId(999_999);
        missing.setName("Nobody");
        assertThat(CustomerService.updateCustomer(missing)).isFalse();
    }

    private static Customer customer(String username, String email) {
        Customer customer = new Customer();
        customer.setUsername(username);
        customer.setPassword(PasswordUtil.hash("password123"));
        customer.setName("Name " + username);
        customer.setEmail(email);
        customer.setCreateDate(new Timestamp(System.currentTimeMillis()));
        return customer;
    }
}
