package com.dijo.events;

import static com.dijo.benefits.PlayerAccountService.requireValidPassword;
import static com.dijo.benefits.PlayerAccountService.requireValidUsername;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of official accounts (ROLE_OFFICIAL, never admins). */
@Service
public class OfficialService {
    /** An official with their match workload. */
    public record Official(String username, boolean enabled, int assigned, int open, int completed) {}

    private static final String OFFICIAL_ONLY = """
            EXISTS (SELECT 1 FROM authorities a
                    WHERE a.username = u.username AND a.authority = 'ROLE_OFFICIAL')
            AND NOT EXISTS (SELECT 1 FROM authorities staff
                            WHERE staff.username = u.username AND staff.authority = 'ROLE_ADMIN')""";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public OfficialService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<Official> officials() {
        return jdbc.query("""
                SELECT u.username, u.enabled,
                  (SELECT count(*) FROM matches m WHERE m.official = u.username) AS assigned,
                  (SELECT count(*) FROM matches m WHERE m.official = u.username
                     AND m.confirmed_at IS NULL) AS open,
                  (SELECT count(*) FROM matches m WHERE m.official = u.username
                     AND m.confirmed_at IS NOT NULL) AS completed
                FROM users u WHERE %s ORDER BY u.username
                """.formatted(OFFICIAL_ONLY), (rs, row) -> new Official(rs.getString("username"),
                rs.getBoolean("enabled"), rs.getInt("assigned"), rs.getInt("open"),
                rs.getInt("completed")));
    }

    /** Open (unconfirmed) non-BYE matches across all events that have no official yet. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public int unassignedMatches() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM matches
                WHERE official IS NULL AND confirmed_at IS NULL
                AND player1_bye = 0 AND player2_bye = 0
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void createOfficial(String username, String password, boolean enabled) {
        requireValidUsername(username);
        requireValidPassword(password);
        int inserted = jdbc.update("""
                INSERT INTO users(username, password, enabled) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, username, encoder.encode(password), enabled ? 1 : 0);
        if (inserted == 0) throw new IllegalArgumentException("Username already exists");
        jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, 'ROLE_OFFICIAL')",
                username);
    }

    /** A null or blank newPassword keeps the current password. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void updateOfficial(String username, boolean enabled, String newPassword) {
        requireOfficial(username);
        jdbc.update("UPDATE users SET enabled = ? WHERE username = ?", enabled ? 1 : 0, username);
        if (newPassword != null && !newPassword.isBlank()) {
            requireValidPassword(newPassword);
            jdbc.update("UPDATE users SET password = ? WHERE username = ?",
                    encoder.encode(newPassword), username);
        }
    }

    /**
     * Officials who have officiated or recorded anything (results, payments, kits,
     * coupons) are kept for the record; disable them instead.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteOfficial(String username) {
        requireOfficial(username);
        Integer history = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM matches
                        WHERE official = ?1 OR result_by = ?1 OR confirmed_by = ?1)
                     + (SELECT count(*) FROM entry_payments WHERE confirmed_by = ?1)
                     + (SELECT count(*) FROM welcome_kits WHERE handed_over_by = ?1)
                     + (SELECT count(*) FROM player_coupons WHERE redeemed_by = ?1)
                """, Integer.class, username);
        if (history != null && history > 0) {
            throw new IllegalArgumentException("This official has matches or records against their"
                    + " name and cannot be deleted; disable the account instead");
        }
        jdbc.update("DELETE FROM authorities WHERE username = ?", username);
        jdbc.update("DELETE FROM users WHERE username = ?", username);
    }

    private void requireOfficial(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users u WHERE u.username = ? AND " + OFFICIAL_ONLY,
                Integer.class, username);
        if (count == null || count == 0) throw new IllegalArgumentException("Official not found");
    }
}
