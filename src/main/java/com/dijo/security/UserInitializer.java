package com.dijo.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(
        name = "app.seed-users",
        havingValue = "true"
)
public class UserInitializer {

    @Bean
    public CommandLineRunner seedUsers(
            JdbcUserDetailsManager users,
            PasswordEncoder encoder,
            TransactionTemplate transactionTemplate) {

        return args -> transactionTemplate.executeWithoutResult(status -> {
            createUser(users, encoder,
                    "admin", "Admin@12345", "ADMIN", "USER");

            createUser(users, encoder,
                    "dijo", "Dijo@12345", "USER");

            createUser(users, encoder,
                    "user", "User@12345", "USER");

            createUser(users, encoder,
                    "official", "Official@12345", "OFFICIAL");

        });
    }

    private void createUser(
            JdbcUserDetailsManager users,
            PasswordEncoder encoder,
            String username,
            String password,
            String... roles) {

        if (!users.userExists(username)) {
            users.createUser(
                    User.withUsername(username)
                            .password(encoder.encode(password))
                            .roles(roles)
                            .build()
            );
        }
    }
}
