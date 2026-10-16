package com.dijo.ui;

import com.dijo.TestData;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import java.util.List;
import java.util.function.Supplier;
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
 * Base for browserless UI tests: views run in-process against the real services
 * and the throwaway database. Annotate tests with @WithMockUser to sign in.
 */
@SpringBootTest
abstract class UiTest extends SpringBrowserlessTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestData.register(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    TestData data;

    @BeforeEach
    void emptyTables() {
        data = new TestData(jdbc, encoder);
        data.emptyTables();
    }

    /** Runs fixture setup through the services as an admin, keeping the test's user. */
    static <T> T asAdmin(Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext admin = SecurityContextHolder.createEmptyContext();
            admin.setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN", "ROLE_USER"));
            SecurityContextHolder.setContext(admin);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    static void asAdmin(Runnable action) {
        asAdmin(() -> {
            action.run();
            return null;
        });
    }

    Button button(String text) {
        return find(Button.class).withText(text).single();
    }

    void click(String buttonText) {
        test(button(buttonText)).click();
    }

    /** Texts of the notifications currently open, oldest first. */
    List<String> notifications() {
        return find(Notification.class).all().stream().map(n -> test(n).getText()).toList();
    }
}
