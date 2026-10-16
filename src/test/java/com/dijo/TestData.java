package com.dijo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The throwaway SQLite database every Spring test runs against, and helpers to
 * fill it. Service and UI tests register the same properties, so they share one
 * cached application context.
 */
public final class TestData {
    /** Child tables first, so foreign keys are never violated. */
    private static final List<String> TABLES = List.of("matches", "player_coupons", "welcome_kits",
            "entry_payments", "draw_slots", "events", "benefit_settings", "authorities", "users");

    private static final Path DATABASE;

    static {
        try {
            DATABASE = Files.createTempFile("scoreboard-test", ".db");
            DATABASE.toFile().deleteOnExit();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("vaadin.launch-browser", () -> "false");
        registry.add("spring.main.banner-mode", () -> "off");
        registry.add("logging.level.root", () -> "WARN");
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public TestData(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public void emptyTables() {
        TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    /** Creates an enabled account with the given roles (without the ROLE_ prefix). */
    public void account(String username, String... roles) {
        jdbc.update("INSERT INTO users(username, password, enabled) VALUES (?, ?, 1)",
                username, encoder.encode("Password@123"));
        for (String role : roles) {
            jdbc.update("INSERT INTO authorities(username, authority) VALUES (?, ?)",
                    username, "ROLE_" + role);
        }
    }

    /** Creates players with a confirmed entry payment, so they can be placed in draws. */
    public void signedUpPlayer(String... usernames) {
        for (String username : usernames) {
            account(username, "USER");
            String paymentId = "pay-" + username;
            jdbc.update("""
                    INSERT INTO entry_payments(id, player_username, payment_reference, amount_paise,
                        confirmed_by, confirmed_at) VALUES (?, ?, ?, 50000, ?, '2026-10-01T10:00:00Z')
                    """, paymentId, username, "REF-" + username, username);
            jdbc.update("INSERT INTO welcome_kits(payment_id) VALUES (?)", paymentId);
        }
    }

    public int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
