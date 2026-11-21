package com.pulse.content.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Plain JdbcClient: one tiny table doesn't need an ORM. */
@Repository
public class AdminUserRepository {

    public record AdminUser(UUID id, String username, String displayName, String passwordHash, String role) {
    }

    private final JdbcClient jdbc;

    public AdminUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AdminUser> findByUsername(String username) {
        return jdbc.sql("SELECT id, username, display_name, password_hash, role FROM admin_users WHERE username = :u")
                .param("u", username)
                .query((rs, n) -> new AdminUser(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("password_hash"), rs.getString("role")))
                .optional();
    }

    public Optional<AdminUser> findById(UUID id) {
        return jdbc.sql("SELECT id, username, display_name, password_hash, role FROM admin_users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new AdminUser(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("password_hash"), rs.getString("role")))
                .optional();
    }

    /** Insert the admin, or update name/hash if they changed in the environment (password rotation). */
    public void upsert(String username, String displayName, String passwordHash) {
        jdbc.sql("""
                        INSERT INTO admin_users (username, display_name, password_hash) VALUES (:u, :d, :h)
                        ON CONFLICT (username) DO UPDATE SET display_name = EXCLUDED.display_name,
                                                             password_hash = EXCLUDED.password_hash
                        """)
                .param("u", username).param("d", displayName).param("h", passwordHash)
                .update();
    }
}
