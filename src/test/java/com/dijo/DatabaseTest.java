package com.dijo;

import java.util.Arrays;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for service tests: the real application context on a throwaway SQLite
 * database, empty tables for every test, and helpers to run code as a given user.
 */
@SpringBootTest
public abstract class DatabaseTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestData.register(registry);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PasswordEncoder encoder;

    private TestData data;

    @BeforeEach
    void emptyTables() {
        data = new TestData(jdbc, encoder);
        data.emptyTables();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    protected void account(String username, String... roles) {
        data.account(username, roles);
    }

    protected void player(String... usernames) {
        Arrays.stream(usernames).forEach(u -> data.account(u, "USER"));
    }

    protected void signedUpPlayer(String... usernames) {
        data.signedUpPlayer(usernames);
    }

    protected int count(String sql, Object... args) {
        return data.count(sql, args);
    }

    /** Makes the given user the signed-in user for the rest of the test. */
    protected static void signIn(String username, String... roles) {
        String[] authorities = Arrays.stream(roles).map(r -> "ROLE_" + r).toArray(String[]::new);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(username, null, authorities));
        SecurityContextHolder.setContext(context);
    }

    /** Runs an action as another user, then restores the previous signed-in user. */
    protected static <T> T as(String username, String role, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            signIn(username, role);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    protected static void as(String username, String role, Runnable action) {
        as(username, role, () -> {
            action.run();
            return null;
        });
    }
}
