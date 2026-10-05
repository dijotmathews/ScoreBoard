package com.dijo.benefits;

import com.dijo.events.DrawService;
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

                // Default benefits, unless an admin has already configured them.
                jdbc.update("""
                        INSERT INTO benefit_settings(id, fee_paise, coupon_count, coupon_description)
                        VALUES (1, 50000, 2, 'Refreshment')
                        ON CONFLICT(id) DO NOTHING
                        """);
                long fee = benefits.settings().orElseThrow().feePaise();
                String eventId = "demo-tournament-12";
                jdbc.update("""
                        INSERT INTO events(id, name, contestant_count)
                        VALUES (?, 'Demo Tournament', 16)
                        ON CONFLICT(id) DO NOTHING
                        """, eventId);
                jdbc.update(DrawService.CREATE_EMPTY_DRAWS, eventId, eventId);
                for (int i = 1; i <= 12; i++) {
                    String username = "player%02d".formatted(i);
                    if (!users.userExists(username)) {
                        users.createUser(User.withUsername(username)
                                .password(encoder.encode("Player%02d@12345".formatted(i)))
                                .roles("USER").build());
                    }
                    Integer signedUp = jdbc.queryForObject(
                            "SELECT count(*) FROM entry_payments WHERE player_username = ?",
                            Integer.class, username);
                    if (signedUp == null || signedUp == 0) {
                        benefits.confirmPayment(username, "DEMO:" + eventId + ":" + username, fee);
                    }
                }
            });
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }
}
