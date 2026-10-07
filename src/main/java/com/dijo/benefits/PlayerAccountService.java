package com.dijo.benefits;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin CRUD for player accounts (ROLE_USER only; staff accounts are off limits). */
@Service
public class PlayerAccountService {
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{3,32}";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public PlayerAccountService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void createPlayer(String username, String password, boolean enabled) {
        requireValidUsername(username);
        requireValidPassword(password);
        int inserted = jdbc.update("""
                INSERT INTO users(username, password, enabled) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, username, encoder.encode(password), enabled ? 1 : 0);
        if (inserted == 0) throw new IllegalArgumentException("Username already exists");
        jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, 'ROLE_USER')",
                username);
    }

    /** A null or blank newPassword keeps the current password. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void updatePlayer(String username, boolean enabled, String newPassword) {
        requirePlayer(username);
        jdbc.update("UPDATE users SET enabled = ? WHERE username = ?", enabled ? 1 : 0, username);
        if (newPassword != null && !newPassword.isBlank()) {
            requireValidPassword(newPassword);
            jdbc.update("UPDATE users SET password = ? WHERE username = ?",
                    encoder.encode(newPassword), username);
        }
    }

    /** Players with confirmed payments are kept for the audit trail; disable them instead. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void deletePlayer(String username) {
        requirePlayer(username);
        Integer payments = jdbc.queryForObject(
                "SELECT count(*) FROM entry_payments WHERE player_username = ?",
                Integer.class, username);
        if (payments != null && payments > 0) {
            throw new IllegalArgumentException(
                    "Player has confirmed payments and cannot be deleted; disable the account instead");
        }
        Integer placed = jdbc.queryForObject(
                "SELECT count(*) FROM draw_slots WHERE player_username = ?", Integer.class, username);
        if (placed != null && placed > 0) {
            throw new IllegalArgumentException(
                    "Player is placed in a draw; remove them from the draw first");
        }
        jdbc.update("DELETE FROM authorities WHERE username = ?", username);
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    private void requirePlayer(String username) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users u
                WHERE u.username = ?
                AND EXISTS (SELECT 1 FROM authorities a
                            WHERE a.username = u.username AND a.authority = 'ROLE_USER')
                AND NOT EXISTS (SELECT 1 FROM authorities staff WHERE staff.username = u.username
                                AND staff.authority IN ('ROLE_ADMIN', 'ROLE_OFFICIAL'))
                """, Integer.class, username);
        if (count == null || count == 0) throw new IllegalArgumentException("Player not found");
    }

    /** Username rule shared by every account type. */
    public static void requireValidUsername(String username) {
        if (username == null || !username.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "Username must be 3-32 letters, digits, '.', '_' or '-'");
        }
    }

    /** Password rule shared by every account type. */
    public static void requireValidPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
