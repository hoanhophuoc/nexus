package com.nexus.server.groups;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKeyPackageRepository implements KeyPackageRepository {
    private final JdbcTemplate jdbc;

    public JdbcKeyPackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(UUID userId, byte[] keyPackage) {
        jdbc.update("""
                INSERT INTO nexus.mls_key_package (user_id, key_package)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET key_package = EXCLUDED.key_package, updated_at = clock_timestamp()
                """, userId, keyPackage);
    }

    @Override
    public Optional<byte[]> find(UUID userId) {
        return jdbc.query("""
                SELECT key_package FROM nexus.mls_key_package WHERE user_id = ?
                """, (rs, row) -> rs.getBytes("key_package"), userId).stream().findFirst();
    }
}
