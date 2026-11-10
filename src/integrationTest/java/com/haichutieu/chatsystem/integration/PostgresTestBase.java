package com.haichutieu.chatsystem.integration;

import com.haichutieu.chatsystem.dto.Customer;
import com.haichutieu.chatsystem.server.SocketServer;
import com.haichutieu.chatsystem.server.dal.CustomerService;
import com.haichutieu.chatsystem.server.dal.HibernateUtil;
import com.haichutieu.chatsystem.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One PostgreSQL 18 container per test JVM. Hibernate is initialized through the
 * same configuration path the server uses, so mappings and SQL are real.
 */
public abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("chat_test")
                    .withUsername("chat")
                    .withPassword("chat");

    protected static final Properties PROPERTIES = new Properties();

    static {
        POSTGRES.start();
        PROPERTIES.setProperty("hibernate.connection.provider_class",
                "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
        PROPERTIES.setProperty("hibernate.hikari.dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
        PROPERTIES.setProperty("hibernate.transaction.coordinator_class", "jdbc");
        PROPERTIES.setProperty("hibernate.hikari.dataSource.url", POSTGRES.getJdbcUrl());
        PROPERTIES.setProperty("hibernate.hikari.dataSource.user", POSTGRES.getUsername());
        PROPERTIES.setProperty("hibernate.hikari.dataSource.password", POSTGRES.getPassword());
        PROPERTIES.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        PROPERTIES.setProperty("hibernate.hikari.maximumPoolSize", "5");
        PROPERTIES.setProperty("hibernate.hikari.minimumIdle", "1");
        PROPERTIES.setProperty("hibernate.query.native.prefer_jdbc_datetime_types", "true");
        PROPERTIES.setProperty("server.host", "localhost");
        PROPERTIES.setProperty("server.port", "0");

        SocketServer.properties = PROPERTIES;
        HibernateUtil.getInstance();
    }

    @BeforeEach
    void cleanDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    truncate table message_display, message, conversation_member, conversation,
                        spam_list, friend_list, block_list, login_time, customer
                    restart identity cascade
                    """);
        }
    }

    protected static Customer createCustomer(String username) {
        return createCustomer(username, "password123", false, PasswordUtil.hash("password123"));
    }

    protected static Customer createAdmin(String username) {
        return createCustomer(username, "password123", true, PasswordUtil.hash("password123"));
    }

    protected static Customer createLegacyCustomer(String username) {
        return createCustomer(username, "admin123", false,
                "$2a$10$OpmVhM0U6OBNC6kmfqchGuW0Ypkx.oz5Q8pJ6RSGmZ/t/7Ox9a/DW");
    }

    private static Customer createCustomer(String username, String rawPassword, boolean admin, String passwordHash) {
        Customer customer = new Customer();
        customer.setUsername(username);
        customer.setPassword(passwordHash);
        customer.setName("Name " + username);
        customer.setEmail(username + "@example.com");
        customer.setCreateDate(new Timestamp(System.currentTimeMillis()));
        customer.setIsLock(false);
        customer.setAdmin(admin);

        assertThat(CustomerService.addCustomer(customer)).isTrue();
        return customer;
    }
}
