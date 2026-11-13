package com.nexus.server.social;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The friend lists page over {@code ORDER BY id} with a cursor, not over an offset, so a page that
 * follows a cursor must continue exactly where the previous one stopped. Every statement also binds
 * its cursor unconditionally: a missing cursor becomes the nil UUID, which sorts below every id a
 * random UUID can produce, so the first page is the same statement with a sentinel value rather than
 * a second SQL text the planner has to re-plan.
 *
 * <p>These assertions run against PostgreSQL because the sentinel only works if the ordering really
 * is by UUID, and because the page boundaries come from the {@code limit + 1} probe row.
 */
@Testcontainers
@SpringBootTest
class SocialPaginationIntegrationTest {

    private static final UUID VIEWER = id(1);

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

    @BeforeEach
    void viewersExist() {
        for (int viewer = 1; viewer <= 5; viewer++) user(viewer);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SocialRepository social;

    @Test
    void friendsContinueAfterTheCursorInsteadOfRepeatingAPage() {
        user(11);
        user(12);
        user(13);
        friend(VIEWER, id(11));
        friend(VIEWER, id(12));
        friend(VIEWER, id(13));

        UserPage<SocialUser> firstPage = social.friends(VIEWER, null, 2);
        assertThat(ids(firstPage.items())).containsExactly(id(11), id(12));
        assertThat(firstPage.nextCursor()).isEqualTo(id(12).toString());

        UserPage<SocialUser> secondPage = social.friends(VIEWER, UUID.fromString(firstPage.nextCursor()), 2);
        assertThat(ids(secondPage.items()))
                .as("the second page starts after the last id of the first one")
                .containsExactly(id(13));
        assertThat(secondPage.nextCursor()).as("the probe row said there is nothing left").isNull();

        UserPage<SocialUser> sentinel = social.friends(VIEWER, new UUID(0L, 0L), 2);
        assertThat(ids(sentinel.items()))
                .as("the nil UUID is the sentinel a missing cursor turns into")
                .containsExactly(id(11), id(12));
    }

    @Test
    void blockedAccountsContinueAfterTheCursor() {
        UUID viewer = id(2);
        user(21);
        user(22);
        user(23);
        block(viewer, id(21));
        block(viewer, id(22));
        block(viewer, id(23));

        UserPage<SocialUser> firstPage = social.blocked(viewer, null, 2);
        assertThat(ids(firstPage.items())).containsExactly(id(21), id(22));
        assertThat(firstPage.nextCursor()).isEqualTo(id(22).toString());

        UserPage<SocialUser> secondPage = social.blocked(viewer, UUID.fromString(firstPage.nextCursor()), 2);
        assertThat(ids(secondPage.items())).containsExactly(id(23));
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void incomingRequestsContinueAfterTheCursor() {
        UUID viewer = id(3);
        user(31);
        user(32);
        user(33);
        request(id(1001), id(31), viewer);
        request(id(1002), id(32), viewer);
        request(id(1003), id(33), viewer);

        FriendRequestPage firstPage = social.incomingRequests(viewer, null, 2);
        assertThat(firstPage.items()).extracting(IncomingFriendRequest::id)
                .containsExactly(id(1001), id(1002));
        assertThat(firstPage.items()).extracting(request -> request.from().id())
                .containsExactly(id(31), id(32));
        assertThat(firstPage.nextCursor()).isEqualTo(id(1002).toString());

        FriendRequestPage secondPage =
                social.incomingRequests(viewer, UUID.fromString(firstPage.nextCursor()), 2);
        assertThat(secondPage.items()).extracting(IncomingFriendRequest::id)
                .containsExactly(id(1003));
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void outgoingRequestsContinueAfterTheCursor() {
        UUID viewer = id(4);
        user(41);
        user(42);
        user(43);
        request(id(2001), viewer, id(41));
        request(id(2002), viewer, id(42));
        request(id(2003), viewer, id(43));

        OutgoingFriendRequestPage firstPage = social.outgoingRequests(viewer, null, 2);
        assertThat(firstPage.items()).extracting(OutgoingFriendRequest::id)
                .containsExactly(id(2001), id(2002));
        assertThat(firstPage.items()).extracting(request -> request.to().id())
                .containsExactly(id(41), id(42));
        assertThat(firstPage.nextCursor()).isEqualTo(id(2002).toString());

        OutgoingFriendRequestPage secondPage =
                social.outgoingRequests(viewer, UUID.fromString(firstPage.nextCursor()), 2);
        assertThat(secondPage.items()).extracting(OutgoingFriendRequest::id)
                .containsExactly(id(2003));
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void aLockedAccountDisappearsFromEveryList() {
        UUID viewer = id(5);
        user(51);
        user(52);
        friend(viewer, id(51));
        friend(viewer, id(52));
        request(id(3001), id(51), viewer);
        jdbc.update("UPDATE nexus.user_account SET locked = TRUE WHERE id = ?", id(51));

        assertThat(ids(social.friends(viewer, null, 10).items()))
                .as("a locked friend is not listed")
                .containsExactly(id(52));
        assertThat(social.incomingRequests(viewer, null, 10).items())
                .as("a request from a locked account is not listed")
                .isEmpty();
    }

    private static List<UUID> ids(List<SocialUser> users) {
        return users.stream().map(SocialUser::id).toList();
    }

    private void user(int number) {
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, 'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                ON CONFLICT (id) DO NOTHING
                """, id(number), "user" + number, "user" + number, "User " + number,
                "user" + number + "@example.com", "user" + number + "@example.com");
    }

    private void friend(UUID first, UUID second) {
        jdbc.update("INSERT INTO nexus.friendship (user_low_id, user_high_id) VALUES (?, ?)",
                pairLow(first, second), pairHigh(first, second));
    }

    private void block(UUID blocker, UUID blocked) {
        jdbc.update("INSERT INTO nexus.user_block (blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }

    private void request(UUID requestId, UUID requester, UUID target) {
        jdbc.update("""
                INSERT INTO nexus.friend_request (id, requester_id, target_id, pair_low_id, pair_high_id)
                VALUES (?, ?, ?, ?, ?)
                """, requestId, requester, target, pairLow(requester, target), pairHigh(requester, target));
    }

    private static UUID pairLow(UUID first, UUID second) {
        return first.compareTo(second) < 0 ? first : second;
    }

    private static UUID pairHigh(UUID first, UUID second) {
        return first.compareTo(second) < 0 ? second : first;
    }

    private static UUID id(int number) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(number));
    }
}
