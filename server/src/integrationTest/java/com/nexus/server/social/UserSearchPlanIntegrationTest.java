package com.nexus.server.social;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search used to filter with POSITION(...) > 0, which no index can serve, so a term that matched
 * nobody walked all 20,000 rows in id order (6.6 ms, linear in the number of users). V9 adds trigram
 * indexes and the predicate is now a substring LIKE, so the plan must reach them instead of the
 * table.
 *
 * <p>The plan is asserted through a prepared statement executed with real values: PostgreSQL plans a
 * parameterized LIKE with default selectivity, so the generic plan falls back to walking the primary
 * key in id order (11.17 ms measured), while a plan built from the actual pattern uses both trigram
 * indexes (0.206 ms). The datasource therefore forces custom plans and this test guards both halves.
 */
@Testcontainers
@SpringBootTest
class UserSearchPlanIntegrationTest {

    private static final int USERS = 20_000;
    private static final UUID VIEWER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID FIRST_PAGE = new UUID(0L, 0L);

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
    private SocialRepository social;

    @Test
    void connectionsPlanEachSearchFromItsOwnPattern() {
        assertThat(jdbc.queryForObject("SHOW plan_cache_mode", String.class))
                .as("a generic plan for this search walks every account instead of the trigram indexes")
                .isEqualTo("force_custom_plan");
    }

    @Test
    void searchReadsOnlyMatchingUsersInsteadOfTheWholeTable() {
        seed();

        String noMatches = planFor("%zzzznomatch%");
        assertThat(noMatches)
                .as("a term nobody has must not walk user_account: %s", noMatches)
                .doesNotContain("Seq Scan on user_account")
                .contains("Bitmap Index Scan on user_account_username_trgm_idx",
                        "Bitmap Index Scan on user_account_full_name_trgm_idx");

        String oneMatch = planFor("%minhhoa%");
        assertThat(oneMatch)
                .as("a term that matches one user must also use the indexes: %s", oneMatch)
                .doesNotContain("Seq Scan on user_account")
                .contains("Bitmap Index Scan on user_account_username_trgm_idx");

        assertThat(social.search(VIEWER, "hoa", null, 25).items())
                .as("substring matching still works, including inside the username")
                .extracting(SocialUser::username)
                .containsExactly("minhhoa");

        assertThat(social.search(VIEWER, "THI", null, 25).items())
                .as("the full-name side still matches case-insensitively")
                .extracting(SocialUser::username)
                .containsExactly("minhhoa");

        assertThat(social.search(VIEWER, "_", null, 25).items())
                .as("a literal underscore must not act as a single-character wildcard")
                .extracting(SocialUser::username)
                .containsExactly("a_b");

        assertThat(social.search(VIEWER, "%", null, 25).items())
                .as("a literal percent sign must not match every user")
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
        insertUser("minhhoa", "Nguyen Thi Hoa");
        insertUser("a_b", "Underscore Person");
        jdbc.execute("ANALYZE nexus.user_account");
    }

    private void insertUser(String username, String fullName) {
        jdbc.update("""
                INSERT INTO nexus.user_account
                    (id, username, username_normalized, full_name, email, email_normalized, address,
                     birth_date, gender, password_hash)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'addr', DATE '1990-01-01', 'undisclosed', 'unused')
                """, username, username, fullName, username + "@example.com", username + "@example.com");
    }

    /**
     * Plans the repository's own SQL with the pattern supplied as a value, which is what the
     * connection pool does at execution time. EXECUTE does not accept bind parameters, so the
     * constants are inlined; nothing here comes from a request.
     */
    private String planFor(String pattern) {
        String literal = "'" + pattern.replace("'", "''") + "'";
        String viewer = "'" + VIEWER + "'";
        jdbc.execute("DEALLOCATE ALL");
        jdbc.execute("PREPARE user_search_plan(uuid, text, text, uuid, uuid, uuid, int) AS "
                + numberedParameters(JdbcSocialRepository.SEARCH_SQL));
        return String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, FORMAT TEXT) EXECUTE user_search_plan("
                        + viewer + ", " + literal + ", " + literal + ", "
                        + viewer + ", " + viewer + ", '" + FIRST_PAGE + "', 51)",
                String.class));
    }

    private static String numberedParameters(String sql) {
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
}
