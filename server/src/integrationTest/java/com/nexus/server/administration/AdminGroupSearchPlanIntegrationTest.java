package com.nexus.server.administration;

import com.nexus.server.LikePatterns;
import com.nexus.server.identity.SessionIdentity;
import com.nexus.server.messaging.ConversationPage;
import com.nexus.server.messaging.ConversationView;
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
 * The admin group list filtered names with POSITION(...) > 0, which no index can serve: a term that
 * matched no group made the planner read every conversation row. V11 added a partial trigram index
 * over lower(name) for groups, so the same term must now plan against it.
 */
@Testcontainers
@SpringBootTest
class AdminGroupSearchPlanIntegrationTest {

    private static final int GROUPS = 20_000;

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
    private AdminService admin;

    @Test
    void adminGroupFilterReadsOnlyMatchingGroups() {
        seed();

        String noMatches = planFor("zzzznomatch");
        assertThat(noMatches)
                .as("a term no group has must not walk conversation: %s", noMatches)
                .doesNotContain("Seq Scan on conversation")
                .contains("conversation_group_name_trgm_idx");

        assertThat(namesMatching("HOA"))
                .as("the name stays case-insensitive")
                .containsExactlyInAnyOrder("Hoa Team");
        assertThat(namesMatching("_"))
                .as("a literal underscore must not act as a single-character wildcard")
                .containsExactlyInAnyOrder("a_b group");
        assertThat(namesMatching("%"))
                .as("a literal percent sign must match only the group that has one")
                .containsExactlyInAnyOrder("100% real");
        assertThat(namesMatching("zzzznomatch"))
                .as("a term no group has returns nothing")
                .isEmpty();
        assertThat(namesMatching("group 19999"))
                .as("an existing group is still found")
                .containsExactlyInAnyOrder("Group 19999");

        // The service must reach the same rows through the filter that was just planned.
        ConversationPage page = admin.groups(
                new SessionIdentity(UUID.randomUUID(), "admin"), "hoa", "name", "asc", null, 10);
        assertThat(page.items()).extracting(ConversationView::name).containsExactly("Hoa Team");
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO nexus.conversation (id, type, name)
                SELECT gen_random_uuid(), 'group', 'Group ' || lpad(i::text, 5, '0')
                FROM generate_series(1, ?) AS i
                """, GROUPS);
        insertGroup("Hoa Team");
        insertGroup("a_b group");
        insertGroup("100% real");
        jdbc.execute("ANALYZE nexus.conversation");
    }

    private void insertGroup(String name) {
        jdbc.update("INSERT INTO nexus.conversation (id, type, name) VALUES (gen_random_uuid(), 'group', ?)",
                name);
    }

    /**
     * Plans the statement the service really runs — the filtered, first-page group list — with a real
     * value, the way the pool runs it. EXECUTE does not accept bind parameters, so the term is
     * inlined into the constant text.
     */
    private String planFor(String term) {
        String sql = AdminSupport.groupPageSql("createdAt", "ASC", true, false);
        jdbc.execute("DEALLOCATE ALL");
        jdbc.execute("PREPARE admin_group_plan(text, int) AS " + numbered(sql));
        return String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, FORMAT TEXT) EXECUTE admin_group_plan(" + literal(LikePatterns.containing(term))
                        + ", 51)", String.class));
    }

    private List<String> namesMatching(String term) {
        return jdbc.query(AdminSupport.groupPageSql("name", "ASC", true, false),
                (rs, row) -> rs.getString("name"), LikePatterns.containing(term), 51);
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
