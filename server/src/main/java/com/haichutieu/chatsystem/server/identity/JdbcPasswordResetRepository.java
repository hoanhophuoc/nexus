package com.haichutieu.chatsystem.server.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPasswordResetRepository implements PasswordResetRepository {

    private final JdbcTemplate jdbc;

    public JdbcPasswordResetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PasswordResetAccount> findAccount(String normalizedEmail) {
        return jdbc.query("""
                SELECT id, email FROM chat_v1.user_account WHERE email_normalized = ?
                """, (rs, row) -> new PasswordResetAccount(rs.getObject("id", UUID.class), rs.getString("email")),
                normalizedEmail).stream().findFirst();
    }

    @Override
    public Optional<PasswordResetAccount> findAccountById(UUID userId) {
        return jdbc.query("""
                SELECT id, email FROM chat_v1.user_account WHERE id = ?
                """, (rs, row) -> new PasswordResetAccount(rs.getObject("id", UUID.class), rs.getString("email")),
                userId).stream().findFirst();
    }

    @Override
    @Transactional
    public void create(String tokenHash, UUID userId, Instant expiresAt) {
        jdbc.update("""
                UPDATE chat_v1.password_reset_token SET used_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND used_at IS NULL
                """, userId);
        jdbc.update("""
                INSERT INTO chat_v1.password_reset_token (token_hash, user_id, expires_at)
                VALUES (?, ?, ?)
                """, tokenHash, userId, Timestamp.from(expiresAt));
    }

    @Override
    public void invalidate(String tokenHash) {
        jdbc.update("""
                UPDATE chat_v1.password_reset_token SET used_at = CURRENT_TIMESTAMP
                WHERE token_hash = ? AND used_at IS NULL
                """, tokenHash);
    }

    @Override
    @Transactional
    public boolean consume(String tokenHash, String newPasswordHash) {
        Optional<UUID> userId = jdbc.query("""
                UPDATE chat_v1.password_reset_token SET used_at = CURRENT_TIMESTAMP
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                RETURNING user_id
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tokenHash).stream().findFirst();
        if (userId.isEmpty()) {
            return false;
        }
        jdbc.update("UPDATE chat_v1.user_account SET password_hash = ? WHERE id = ?", newPasswordHash, userId.get());
        jdbc.update("""
                UPDATE chat_v1.user_session SET revoked_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND revoked_at IS NULL
                """, userId.get());
        return true;
    }
}
