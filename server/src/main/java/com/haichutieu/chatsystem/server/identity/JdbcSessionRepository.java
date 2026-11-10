package com.haichutieu.chatsystem.server.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(String tokenHash, UUID userId, Instant expiresAt) {
        jdbc.update("INSERT INTO chat_v1.user_session (session_hash, user_id, expires_at) VALUES (?, ?, ?)",
                tokenHash, userId, Timestamp.from(expiresAt));
        jdbc.update("INSERT INTO chat_v1.login_history (id, user_id) VALUES (?, ?)", UUID.randomUUID(), userId);
    }

    @Override
    public Optional<SessionIdentity> authenticate(String tokenHash) {
        return jdbc.query("""
                SELECT u.id, u.role
                FROM chat_v1.user_session s JOIN chat_v1.user_account u ON u.id = s.user_id
                WHERE s.session_hash = ? AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP
                  AND u.locked = FALSE
                """, (rs, row) -> new SessionIdentity(rs.getObject("id", UUID.class), rs.getString("role")),
                tokenHash).stream().findFirst();
    }

    @Override
    public void revoke(String tokenHash) {
        jdbc.update("UPDATE chat_v1.user_session SET revoked_at = CURRENT_TIMESTAMP WHERE session_hash = ? AND revoked_at IS NULL",
                tokenHash);
    }

    @Override
    @Transactional
    public boolean changePassword(UUID userId, String expectedHash, String newHash, String currentSessionHash) {
        int updated = jdbc.update("""
                UPDATE chat_v1.user_account u SET password_hash = ?
                WHERE u.id = ? AND u.password_hash = ? AND u.locked = FALSE
                  AND EXISTS (SELECT 1 FROM chat_v1.user_session s
                              WHERE s.user_id = u.id AND s.session_hash = ?
                                AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP)
                """, newHash, userId, expectedHash, currentSessionHash);
        if (updated == 0) {
            return false;
        }
        jdbc.update("""
                UPDATE chat_v1.user_session SET revoked_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND session_hash <> ? AND revoked_at IS NULL
                """, userId, currentSessionHash);
        return true;
    }
}
