package com.nexus.server.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat list and the message history bind a first-page sentinel now that their statements are
 * constant (a nil UUID, and sequence 0), so the first page is the same SQL text as every later page.
 * That only stays correct if the sentinel really sorts before every row and if the cursor still
 * continues exactly after the last row of the previous page, so this test walks both lists page by
 * page and compares the sentinel page with the page a missing cursor produces.
 *
 * <p>The history paging runs on a direct conversation because that is the only kind this repository
 * reads; group history is served by the MLS repository. Each test uses its own viewer so the shared
 * database cannot leak rows from a test that already ran.
 */
@Testcontainers
@SpringBootTest
class MessagingPaginationIntegrationTest {

    private static final UUID LISTER = id(3);
    private static final UUID LEAVER = id(4);
    private static final UUID VIEWER = id(1);
    private static final UUID SENDER = id(2);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MessagingRepository messaging;

    @BeforeEach
    void accountsExist() {
        for (int account = 1; account <= 5; account++) {
            user(account);
        }
    }

    @Test
    void conversationListStartsAtTheSentinelAndContinuesAfterTheCursor() {
        for (int conversation = 101; conversation <= 105; conversation++) {
            groupConversation(conversation, LISTER);
        }

        ConversationPage first = messaging.conversations(LISTER, null, 2);
        assertThat(ids(first.items())).containsExactly(id(101), id(102));
        assertThat(first.nextCursor()).isEqualTo(id(102).toString());

        List<UUID> walked = new ArrayList<>(ids(first.items()));
        String cursor = first.nextCursor();
        while (cursor != null) {
            ConversationPage page = messaging.conversations(LISTER, UUID.fromString(cursor), 2);
            walked.addAll(ids(page.items()));
            cursor = page.nextCursor();
        }
        assertThat(walked)
                .as("walking every page visits each conversation once, in id order")
                .containsExactly(id(101), id(102), id(103), id(104), id(105));

        ConversationPage sentinel = messaging.conversations(LISTER, new UUID(0L, 0L), 2);
        assertThat(ids(sentinel.items()))
                .as("the nil UUID is the sentinel a missing cursor turns into")
                .isEqualTo(ids(first.items()));
    }

    @Test
    void conversationListHidesConversationsTheViewerLeft() {
        groupConversation(111, LEAVER);
        groupConversation(112, LEAVER);
        jdbc.update("DELETE FROM nexus.conversation_member WHERE conversation_id = ? AND user_id = ?",
                id(112), LEAVER);

        assertThat(ids(messaging.conversations(LEAVER, null, 10).items())).containsExactly(id(111));
    }

    @Test
    void messageHistoryStartsWithTheOldestMessageAndContinuesBySequence() {
        directConversation(201, SENDER);
        for (int message = 1; message <= 5; message++) {
            message(201, message);
        }

        MessagePage first = messaging.messages(VIEWER, id(201), null, 2);
        assertThat(first.items()).extracting(MessageView::content)
                .as("a first page starts at sequence 0, so it begins with the oldest message")
                .containsExactly("message 1", "message 2");
        assertThat(first.nextCursor()).isEqualTo(id(201 * 100 + 2).toString());

        List<String> walked = new ArrayList<>(contents(first.items()));
        String cursor = first.nextCursor();
        while (cursor != null) {
            MessagePage page = messaging.messages(VIEWER, id(201), UUID.fromString(cursor), 2);
            walked.addAll(contents(page.items()));
            cursor = page.nextCursor();
        }
        assertThat(walked)
                .as("walking every page returns each message once, oldest first, with none lost")
                .containsExactly("message 1", "message 2", "message 3", "message 4", "message 5");
    }

    @Test
    void messageHistoryHonoursTheSequenceOfTheCursorMessage() {
        directConversation(202, id(5));
        for (int message = 1; message <= 5; message++) {
            message(202, message);
        }

        MessagePage page = messaging.messages(VIEWER, id(202), id(202 * 100 + 3), 10);

        assertThat(page.items()).extracting(MessageView::content)
                .as("the cursor is an id, but the page continues from that message's sequence")
                .containsExactly("message 4", "message 5");
    }

    private void user(int account) {
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, 'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                ON CONFLICT (id) DO NOTHING
                """, id(account), "user" + account, "user" + account, "User " + account,
                "user" + account + "@example.com", "user" + account + "@example.com");
    }

    private void groupConversation(int number, UUID member) {
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, name) VALUES (?, 'group', ?)
                ON CONFLICT (id) DO NOTHING
                """, id(number), "group " + number);
        jdbc.update("""
                INSERT INTO nexus.conversation_member (conversation_id, user_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, id(number), member);
    }

    private void directConversation(int number, UUID peer) {
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, direct_low_id, direct_high_id)
                VALUES (?, 'direct', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id(number), VIEWER, peer);
        for (UUID member : List.of(VIEWER, peer)) {
            jdbc.update("""
                    INSERT INTO nexus.conversation_member (conversation_id, user_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, id(number), member);
        }
    }

    private void message(int conversation, int sequence) {
        jdbc.update("""
                INSERT INTO nexus.message
                    (id, conversation_id, sender_id, conversation_sequence, idempotency_key, content)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id(conversation * 100 + sequence), id(conversation), VIEWER, sequence, "k" + sequence,
                "message " + sequence);
    }

    private static List<String> contents(List<MessageView> messages) {
        return messages.stream().map(MessageView::content).toList();
    }

    private static List<UUID> ids(List<ConversationView> conversations) {
        return conversations.stream().map(ConversationView::id).toList();
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }
}
