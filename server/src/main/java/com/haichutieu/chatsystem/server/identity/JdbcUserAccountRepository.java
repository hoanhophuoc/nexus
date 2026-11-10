package com.haichutieu.chatsystem.server.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserProfile create(NewUserRegistration user) {
        return jdbc.queryForObject("""
                INSERT INTO chat_v1.user_account
                    (id, username, username_normalized, full_name, email, email_normalized,
                     address, birth_date, gender, password_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, username, full_name, email, address, birth_date, gender
                """, (rs, row) -> new UserProfile(
                rs.getObject("id", java.util.UUID.class), rs.getString("username"),
                rs.getString("full_name"), rs.getString("email"), rs.getString("address"),
                rs.getObject("birth_date", java.time.LocalDate.class), rs.getString("gender"), "offline"),
                user.id(), user.username(), user.usernameNormalized(), user.fullName(), user.email(),
                user.emailNormalized(), user.address(), user.birthDate(), user.gender(), user.passwordHash());
    }

    @Override
    public Optional<LoginAccount> findLogin(String normalizedUsername) {
        return jdbc.query("""
                SELECT id, username, full_name, email, address, birth_date, gender, password_hash, locked
                FROM chat_v1.user_account WHERE username_normalized = ?
                """, (rs, row) -> new LoginAccount(new UserProfile(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("address"), rs.getObject("birth_date", java.time.LocalDate.class),
                rs.getString("gender"), "offline"), rs.getString("password_hash"), rs.getBoolean("locked")),
                normalizedUsername).stream().findFirst();
    }

    @Override
    public Optional<UserProfile> findProfile(UUID userId) {
        return jdbc.query("""
                SELECT id, username, full_name, email, address, birth_date, gender
                FROM chat_v1.user_account WHERE id = ? AND locked = FALSE
                """, JdbcUserAccountRepository::mapProfile, userId).stream().findFirst();
    }

    @Override
    public Optional<UserProfile> updateProfile(UUID userId, ProfileUpdateRequest update) {
        return jdbc.query("""
                UPDATE chat_v1.user_account
                SET full_name = COALESCE(?::varchar, full_name),
                    email = COALESCE(?::varchar, email),
                    email_normalized = COALESCE(?::varchar, email_normalized),
                    address = COALESCE(?::varchar, address),
                    birth_date = COALESCE(?::date, birth_date),
                    gender = COALESCE(?::varchar, gender)
                WHERE id = ? AND locked = FALSE
                RETURNING id, username, full_name, email, address, birth_date, gender
                """, JdbcUserAccountRepository::mapProfile, update.fullName(), update.email(),
                normalize(update.email()), update.address(), update.birthDate(), update.gender(), userId)
                .stream().findFirst();
    }

    @Override
    public Optional<String> findPasswordHash(UUID userId) {
        return jdbc.query("SELECT password_hash FROM chat_v1.user_account WHERE id = ? AND locked = FALSE",
                (rs, row) -> rs.getString("password_hash"), userId).stream().findFirst();
    }

    private static UserProfile mapProfile(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UserProfile(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("full_name"), rs.getString("email"), rs.getString("address"),
                rs.getObject("birth_date", java.time.LocalDate.class), rs.getString("gender"), "offline");
    }

    private static String normalize(String email) {
        return email == null ? null : email.toLowerCase(java.util.Locale.ROOT);
    }
}
