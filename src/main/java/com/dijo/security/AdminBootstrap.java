package com.dijo.security;

import com.dijo.benefits.PlayerAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin on an empty database, from app.bootstrap-admin-password
 * (for example the APP_BOOTSTRAP_ADMIN_PASSWORD environment variable). Once any admin
 * exists it does nothing, so the password can be changed in the app afterwards.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final JdbcUserDetailsManager users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AdminBootstrap(JdbcTemplate jdbc, JdbcUserDetailsManager users, PasswordEncoder encoder,
            @Value("${app.bootstrap-admin-username:admin}") String username,
            @Value("${app.bootstrap-admin-password:}") String password) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) return;
        Integer admins = jdbc.queryForObject(
                "SELECT count(*) FROM authorities WHERE authority = 'ROLE_ADMIN'", Integer.class);
        if (admins != null && admins > 0) return;

        PlayerAccountService.requireValidUsername(username);
        PlayerAccountService.requireValidPassword(password);
        if (users.userExists(username)) {
            throw new IllegalStateException("Cannot create the first admin: " + username
                    + " already exists without the admin role");
        }
        users.createUser(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN", "USER")
                .build());
        log.info("Created the first admin account: {}", username);
    }
}
