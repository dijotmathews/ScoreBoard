package com.dijo.benefits;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD for player accounts (ROLE_USER only; staff accounts are off limits), and the
 * signed-in player's own contact number.
 */
@Service
public class PlayerAccountService {
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MAX_USERNAME_LENGTH = 32;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{3,32}";
    private static final int MAX_FULL_NAME_LENGTH = 64;
    private static final String FULL_NAME_PATTERN = "(?=.*\\p{L})[\\p{L}\\p{M}.' -]+";
    /** Digits with optional spaces, '-', '(' and ')', and a leading '+' for the country code. */
    private static final String PHONE_PATTERN = "\\+?[0-9 ()-]+";
    private static final int MIN_PHONE_DIGITS = 7;
    private static final int MAX_PHONE_DIGITS = 15;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public PlayerAccountService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /**
     * Creates a player from their full name. The username is the full name in lowercase
     * without spaces or special characters ("Dijo T Mathews" → "dijotmathews"), with a
     * number added when it is taken ("dijotmathews2"). The initial password is the
     * username. Returns the username.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public String createPlayer(String fullName, boolean enabled) {
        return createPlayer(fullName, null, enabled);
    }

    /** As {@link #createPlayer(String, boolean)}, with an optional contact number. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public String createPlayer(String fullName, String phone, boolean enabled) {
        String name = normalizeFullName(fullName);
        if (name == null) throw new IllegalArgumentException("Enter the player's full name");
        String contact = normalizePhone(phone);
        String username = uniqueUsername(usernameFor(name));
        // The initial password is the username, which may be shorter than the password rule.
        jdbc.update("INSERT INTO users(username, password, enabled, full_name, phone) VALUES (?, ?, ?, ?, ?)",
                username, encoder.encode(username), enabled ? 1 : 0, name, contact);
        jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, 'ROLE_USER')",
                username);
        return username;
    }

    /** The full name in lowercase, keeping only a-z and 0-9 (accents are dropped: é → e). */
    public static String usernameFor(String fullName) {
        String username = Normalizer.normalize(fullName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (username.length() < MIN_USERNAME_LENGTH) {
            throw new IllegalArgumentException("The full name must contain at least "
                    + MIN_USERNAME_LENGTH + " English letters or digits to make a username");
        }
        return username.substring(0, Math.min(username.length(), MAX_USERNAME_LENGTH));
    }

    /** base, or base followed by the first free number from 2, within the length limit. */
    private String uniqueUsername(String base) {
        String candidate = base;
        for (int n = 2; taken(candidate); n++) {
            String suffix = String.valueOf(n);
            candidate = base.substring(0, Math.min(base.length(), MAX_USERNAME_LENGTH - suffix.length()))
                    + suffix;
        }
        return candidate;
    }

    private boolean taken(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ? COLLATE NOCASE", Integer.class, username);
        return count != null && count > 0;
    }

    /**
     * A null or blank newPassword keeps the current password; a blank fullName or phone
     * removes it.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void updatePlayer(String username, String fullName, String phone, boolean enabled,
            String newPassword) {
        requirePlayer(username);
        String name = normalizeFullName(fullName);
        String contact = normalizePhone(phone);
        jdbc.update("UPDATE users SET enabled = ?, full_name = ?, phone = ? WHERE username = ?",
                enabled ? 1 : 0, name, contact, username);
        if (newPassword != null && !newPassword.isBlank()) {
            requireValidPassword(newPassword);
            jdbc.update("UPDATE users SET password = ? WHERE username = ?",
                    encoder.encode(newPassword), username);
        }
    }

    /** The signed-in user's contact number, or null when they have none. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public String myPhone() {
        // A list, as the phone may be NULL (findFirst would reject that).
        var phones = jdbc.queryForList("SELECT phone FROM users WHERE username = ?", String.class, me());
        return phones.isEmpty() ? null : phones.getFirst();
    }

    /** Sets the signed-in user's own contact number; blank removes it. Returns it as stored. */
    @Transactional
    @PreAuthorize("hasRole('USER')")
    public String updateMyPhone(String phone) {
        String contact = normalizePhone(phone);
        jdbc.update("UPDATE users SET phone = ? WHERE username = ?", contact, me());
        return contact;
    }

    private static String me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Authentication required");
        }
        return authentication.getName();
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
                "SELECT count(*) FROM draw_slots WHERE ? IN (player_username, partner_username)",
                Integer.class, username);
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

    /**
     * The name shown for a player, e.g. "Dijo T Mathews": trimmed, with runs of spaces
     * collapsed. Blank means no full name, so the username is shown instead.
     */
    static String normalizeFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String name = fullName.strip().replaceAll("\\s+", " ");
        if (name.length() > MAX_FULL_NAME_LENGTH || !name.matches(FULL_NAME_PATTERN)) {
            throw new IllegalArgumentException("Full name must be at most " + MAX_FULL_NAME_LENGTH
                    + " letters, spaces, apostrophes, '.' or '-'");
        }
        return name;
    }

    /**
     * A contact number as entered, e.g. "+91 98765 43210": trimmed, with runs of spaces
     * collapsed. Blank means no number.
     */
    public static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String number = phone.strip().replaceAll("\\s+", " ");
        long digits = number.chars().filter(Character::isDigit).count();
        if (!number.matches(PHONE_PATTERN) || digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS) {
            throw new IllegalArgumentException("Contact number must have " + MIN_PHONE_DIGITS + "-"
                    + MAX_PHONE_DIGITS + " digits, optionally with a leading '+', spaces, '-' or brackets");
        }
        return number;
    }

    /** Password rule shared by every account type. */
    public static void requireValidPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
