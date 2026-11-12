package com.nexus.server.administration;

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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin account list filters with the same substring predicates the user search used to have:
 * POSITION(...) > 0 over username, full name and email, which no index can serve. V9 indexed the
 * username and full name, V10 the email, and the filter now uses them, so a term that matches
 * nothing must plan against the trigram indexes instead of walking user_account.
 */
@Testcontainers
@SpringBootTest
class AdminUserSearchPlanIntegrationTest {

    private static final int USERS = 20_000;

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

    @Test
    void adminFilterReadsOnlyMatchingAccounts() {
        seed();

        String noMatches = planFor("zzzznomatch");
        assertThat(noMatches)
                .as("a term nobody has must not walk user_account: %s", noMatches)
                .doesNotContain("Seq Scan on user_account")
                .contains("Bitmap Index Scan on user_account_username_trgm_idx",
                        "Bitmap Index Scan on user_account_full_name_trgm_idx",
                        "Bitmap Index Scan on user_account_email_trgm_idx");

        assertThat(usernamesMatching("HOA"))
                .as("the username and full name stay case-insensitive")
                .containsExactly("minhhoa");
        assertThat(usernamesMatching("special-tag"))
                .as("the email is matched through its own index")
                .containsExactly("mailuser");
        assertThat(usernamesMatching("_"))
                .as("a literal underscore must not act as a single-character wildcard")
                .containsExactly("a_b");
        assertThat(usernamesMatching("%"))
                .as("a literal percent sign must not match every account")
                .isEmpty();
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                SELECT gen_random_uuid(), 'user' || lpad(i::text, 5, '0'), 'user' || lpad(i::text, 5, '0'),
                       'User ' || lpad(i::text, 5, '0'), 'user' || i || '@example.com',
                       'user' || i || '@example.com', 'addr', DATE '1990-01-01', 'undisclosed', 'unused'
                FROM generate_series(1, ?) AS i
                """, USERS);
        insertUser("minhhoa", "Nguyen Thi Hoa", "minhhoa@example.com");
        insertUser("a_b", "Underscore Person", "a_b@example.com");
        insertUser("mailuser", "Mai Ly", "special-tag@corp.test");
        jdbc.execute("ANALYZE nexus.user_account");
    }

    private void insertUser(String username, String fullName, String email) {
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                """, username, username, fullName, email, email);
    }

    /**
     * Plans the filter fragment the service builds, executed with real values the way the pool runs
     * it. EXECUTE does not accept bind parameters, so these test constants are inlined.
     */
    private String planFor(String term) {
        StringBuilder where = new StringBuilder("WHERE TRUE");
        List<Object> args = new ArrayList<>();
        AdminSupport.appendUserFilter(where, args, term);
        String prepared = numbered(where.toString());
        String values = args.stream().map(AdminUserSearchPlanIntegrationTest::literal)
                .collect(Collectors.joining(", "));
        jdbc.execute("DEALLOCATE ALL");
        jdbc.execute("PREPARE admin_search_plan(text, text, text, int) AS "
                + "SELECT u.id FROM nexus.user_account u " + prepared
                + " ORDER BY u.id LIMIT $" + (args.size() + 1));
        return String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, FORMAT TEXT) EXECUTE admin_search_plan(" + values + ", 51)", String.class));
    }

    private List<String> usernamesMatching(String term) {
        StringBuilder where = new StringBuilder("WHERE TRUE");
        List<Object> args = new ArrayList<>();
        AdminSupport.appendUserFilter(where, args, term);
        args.add(51);
        return jdbc.queryForList("SELECT u.username FROM nexus.user_account u " + where
                + " ORDER BY u.id LIMIT ?", String.class, args.toArray());
    }

    private static String numbered(String sql) {
        StringBuilder numbered = new StringBuilder(sql.length() + 8);
        int parameter = 1;
        for (char character : sql.toCharArray()) {
            if (character == '?') {
                numbered.append('$').append(parameter++);
            } else {
                numbered.append(character);
            }
        }
        return numbered.toString();
    }

    private static String literal(Object value) {
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
