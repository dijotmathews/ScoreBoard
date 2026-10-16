package com.dijo.benefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class PlayerAccountServiceTest extends DatabaseTest {
    @Autowired
    PlayerAccountService accounts;

    @Autowired
    PlayerBenefitsService benefits;

    @BeforeEach
    void setUp() {
        account("admin", "ADMIN", "USER");
        account("ref", "OFFICIAL");
        signIn("admin", "ADMIN", "USER");
    }

    private String passwordOf(String username) {
        return jdbc.queryForObject("SELECT password FROM users WHERE username = ?", String.class, username);
    }

    @Test
    void createsAnEnabledPlayerWithAnEncodedPassword() {
        accounts.createPlayer("ada", "Secret@123", true);

        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada' AND enabled = 1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM authorities WHERE username = 'ada' AND authority = 'ROLE_USER'"))
                .isEqualTo(1);
        assertThat(encoder.matches("Secret@123", passwordOf("ada"))).isTrue();
    }

    @Test
    void createsADisabledPlayer() {
        accounts.createPlayer("ada", "Secret@123", false);
        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada' AND enabled = 0")).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateAndInvalidUsernames() {
        accounts.createPlayer("ada", "Secret@123", true);

        assertThatThrownBy(() -> accounts.createPlayer("ada", "Secret@123", true))
                .hasMessage("Username already exists");
        assertThatThrownBy(() -> accounts.createPlayer("ab", "Secret@123", true))
                .hasMessage("Username must be 3-32 letters, digits, '.', '_' or '-'");
        assertThatThrownBy(() -> accounts.createPlayer("has space", "Secret@123", true))
                .hasMessage("Username must be 3-32 letters, digits, '.', '_' or '-'");
    }

    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> accounts.createPlayer("ada", "short", true))
                .hasMessage("Password must be at least 8 characters");
    }

    @Test
    void updateKeepsThePasswordWhenBlank() {
        accounts.createPlayer("ada", "Secret@123", true);
        String before = passwordOf("ada");

        accounts.updatePlayer("ada", false, "  ");

        assertThat(passwordOf("ada")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada' AND enabled = 0")).isEqualTo(1);
    }

    @Test
    void updateChangesThePassword() {
        accounts.createPlayer("ada", "Secret@123", true);
        accounts.updatePlayer("ada", true, "Another@123");
        assertThat(encoder.matches("Another@123", passwordOf("ada"))).isTrue();
    }

    @Test
    void staffAccountsAreNotPlayers() {
        assertThatThrownBy(() -> accounts.updatePlayer("ref", false, null)).hasMessage("Player not found");
        assertThatThrownBy(() -> accounts.updatePlayer("admin", false, null)).hasMessage("Player not found");
        assertThatThrownBy(() -> accounts.deletePlayer("ref")).hasMessage("Player not found");
    }

    @Test
    void deletesAPlayerWithNoHistory() {
        accounts.createPlayer("ada", "Secret@123", true);
        accounts.deletePlayer("ada");
        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada'")).isZero();
        assertThat(count("SELECT count(*) FROM authorities WHERE username = 'ada'")).isZero();
    }

    @Test
    void keepsPlayersWhoHavePaid() {
        accounts.createPlayer("ada", "Secret@123", true);
        benefits.updateSettings(50000, 0, "Refreshment");
        benefits.confirmPayment("ada", "UPI-1", 50000);

        assertThatThrownBy(() -> accounts.deletePlayer("ada"))
                .hasMessage("Player has confirmed payments and cannot be deleted; disable the account instead");
    }

    @Test
    void keepsPlayersPlacedInADraw() {
        accounts.createPlayer("ada", "Secret@123", true);
        jdbc.update("INSERT INTO events(id, name, contestant_count) VALUES ('e', 'Event', 2)");
        jdbc.update("INSERT INTO draw_slots(event_id, slot, player_username) VALUES ('e', 1, 'ada')");

        assertThatThrownBy(() -> accounts.deletePlayer("ada"))
                .hasMessage("Player is placed in a draw; remove them from the draw first");
    }

    @Test
    void isAdminOnly() {
        signIn("ref", "OFFICIAL");
        assertThatThrownBy(() -> accounts.createPlayer("ada", "Secret@123", true))
                .isInstanceOf(AccessDeniedException.class);
    }
}
