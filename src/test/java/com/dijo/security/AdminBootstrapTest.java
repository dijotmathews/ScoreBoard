package com.dijo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.provisioning.JdbcUserDetailsManager;

class AdminBootstrapTest extends DatabaseTest {
    @Autowired
    JdbcUserDetailsManager users;

    private void bootstrap(String password) {
        new AdminBootstrap(jdbc, users, encoder, "admin", password).run(new DefaultApplicationArguments());
    }

    @Test
    void createsTheFirstAdminOnAnEmptyDatabase() {
        bootstrap("Secret@2026");

        assertThat(count("SELECT count(*) FROM authorities WHERE username = 'admin'")).isEqualTo(2);
        assertThat(encoder.matches("Secret@2026", users.loadUserByUsername("admin").getPassword())).isTrue();
    }

    @Test
    void leavesAnExistingAdminAlone() {
        account("boss", "ADMIN", "USER");
        bootstrap("Secret@2026");

        assertThat(users.userExists("admin")).isFalse();
    }

    @Test
    void doesNothingWithoutAPassword() {
        bootstrap("");
        assertThat(count("SELECT count(*) FROM users")).isZero();
    }

    @Test
    void refusesAWeakPasswordOrATakenUsername() {
        assertThatThrownBy(() -> bootstrap("short")).hasMessage("Password must be at least 8 characters");

        player("admin");
        assertThatThrownBy(() -> bootstrap("Secret@2026"))
                .hasMessage("Cannot create the first admin: admin already exists without the admin role");
    }
}
