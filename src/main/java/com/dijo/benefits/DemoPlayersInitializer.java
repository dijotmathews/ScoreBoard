package com.dijo.benefits;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicitly enabled development fixture; never records real payments. */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.seed-demo-players", havingValue = "true")
public class DemoPlayersInitializer implements CommandLineRunner {
    private final JdbcUserDetailsManager users;
    private final PasswordEncoder encoder;
    private final PlayerBenefitsService benefits;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DemoPlayersInitializer(JdbcUserDetailsManager users, PasswordEncoder encoder,
            PlayerBenefitsService benefits, JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.users = users;
        this.encoder = encoder;
        this.benefits = benefits;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void run(String... args) {
        var previousContext = SecurityContextHolder.getContext();
        try {
            transaction.executeWithoutResult(status -> {
                if (!users.userExists("admin")) {
                    users.createUser(User.withUsername("admin")
                            .password(encoder.encode("Admin@12345"))
                            .roles("ADMIN", "USER").build());
                }
                var admin = users.loadUserByUsername("admin");
                if (!admin.isEnabled() || admin.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
                    throw new IllegalStateException("Demo seed requires an enabled admin account");
                }
                // Trusted dev-only startup context for the role-protected service.
                // This is not an HTTP login or an authentication endpoint.
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        admin, null, admin.getAuthorities()));
                SecurityContextHolder.setContext(context);

                String eventId = "demo-tournament-12";
                jdbc.update("""
                        INSERT INTO benefit_events
                        (id, name, fee_paise, coupon_count, coupon_description)
                        VALUES (?, 'Demo Tournament', 50000, 2, 'Refreshment')
                        ON CONFLICT(id) DO NOTHING
                        """, eventId);
                Integer validEvent = jdbc.queryForObject("""
                        SELECT count(*) FROM benefit_events WHERE id = ?
                        AND fee_paise = 50000 AND coupon_count = 2
                        AND coupon_description = 'Refreshment'
                        """, Integer.class, eventId);
                if (validEvent == null || validEvent != 1) {
                    throw new IllegalStateException("Demo event already exists with different settings");
                }
                for (int i = 1; i <= 12; i++) {
                    String username = "player%02d".formatted(i);
                    if (!users.userExists(username)) {
                        users.createUser(User.withUsername(username)
                                .password(encoder.encode("Player%02d@12345".formatted(i)))
                                .roles("USER").build());
                    }
                    benefits.confirmPayment(eventId, username,
                            "DEMO:" + eventId + ":" + username, 50000);
                }
            });
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }
}
