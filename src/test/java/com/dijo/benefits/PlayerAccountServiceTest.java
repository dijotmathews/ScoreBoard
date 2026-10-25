package com.dijo.benefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void createsAnEnabledPlayerFromTheirFullName() {
        assertThat(accounts.createPlayer("  Dijo   T Mathews ", true)).isEqualTo("dijotmathews");

        assertThat(count("SELECT count(*) FROM users WHERE username = 'dijotmathews' AND enabled = 1"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM authorities WHERE username = 'dijotmathews'"
                + " AND authority = 'ROLE_USER'")).isEqualTo(1);
        var row = benefits.listPlayerBenefits().getFirst();
        assertThat(row.fullName()).isEqualTo("Dijo T Mathews");
        assertThat(row.name()).isEqualTo("Dijo T Mathews");
    }

    @Test
    void theInitialPasswordIsTheUsername() {
        accounts.createPlayer("Ada Li", true);
        assertThat(encoder.matches("adali", passwordOf("adali"))).isTrue();
    }

    @Test
    void createsADisabledPlayer() {
        accounts.createPlayer("Ada", false);
        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada' AND enabled = 0")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Dijo T. Mathews       | dijotmathews",
            "Ada O'Neil-Lovelace   | adaoneillovelace",
            "José Müller           | josemuller",
            "Player 7              | player7",
            "Abcdefghij Klmnopqrst Uvwxyzabcd Efgh | abcdefghijklmnopqrstuvwxyzabcdef"})
    void usernamesKeepOnlyLowercaseLettersAndDigits(String fullName, String username) {
        assertThat(PlayerAccountService.usernameFor(fullName)).isEqualTo(username);
    }

    @Test
    void addsANumberWhenTheUsernameIsTaken() {
        assertThat(accounts.createPlayer("Dijo T Mathews", true)).isEqualTo("dijotmathews");
        assertThat(accounts.createPlayer("Dijo T. Mathews", true)).isEqualTo("dijotmathews2");
        assertThat(accounts.createPlayer("dijo t mathews", true)).isEqualTo("dijotmathews3");
        // Staff usernames are taken too.
        assertThat(accounts.createPlayer("Ref", true)).isEqualTo("ref2");
        assertThat(encoder.matches("dijotmathews2", passwordOf("dijotmathews2"))).isTrue();
    }

    @Test
    void keepsNumberedUsernamesWithinTheLengthLimit() {
        String name = "Abcdefghij Klmnopqrst Uvwxyzabcd Efgh";
        accounts.createPlayer(name, true);
        assertThat(accounts.createPlayer(name, true)).isEqualTo("abcdefghijklmnopqrstuvwxyzabcde2");
    }

    @Test
    void requiresAFullNameThatGivesAUsername() {
        assertThatThrownBy(() -> accounts.createPlayer("   ", true))
                .hasMessage("Enter the player's full name");
        assertThatThrownBy(() -> accounts.createPlayer("Al", true))
                .hasMessage("The full name must contain at least 3 English letters or digits to make a username");
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
    }

    @Test
    void updateChangesAndClearsTheFullName() {
        accounts.createPlayer("Ada", true);

        accounts.updatePlayer("ada", "Ada O'Neil-Lovelace", true, null);
        assertThat(benefits.listPlayerBenefits().getFirst().fullName()).isEqualTo("Ada O'Neil-Lovelace");

        accounts.updatePlayer("ada", "", true, null);
        var row = benefits.listPlayerBenefits().getFirst();
        assertThat(row.fullName()).isNull();
        assertThat(row.name()).isEqualTo("ada");
    }

    @Test
    void rejectsInvalidFullNames() {
        String message = "Full name must be at most 64 letters, spaces, apostrophes, '.' or '-'";
        assertThatThrownBy(() -> accounts.createPlayer("Ada <script>", true)).hasMessage(message);
        assertThatThrownBy(() -> accounts.createPlayer("- .", true)).hasMessage(message);
        assertThatThrownBy(() -> accounts.createPlayer("a".repeat(65), true)).hasMessage(message);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
    }

    @Test
    void rejectsShortNewPasswords() {
        accounts.createPlayer("Ada", true);
        assertThatThrownBy(() -> accounts.updatePlayer("ada", null, true, "short"))
                .hasMessage("Password must be at least 8 characters");
    }

    @Test
    void updateKeepsThePasswordWhenBlank() {
        accounts.createPlayer("Ada", true);
        String before = passwordOf("ada");

        accounts.updatePlayer("ada", null, false, "  ");

        assertThat(passwordOf("ada")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada' AND enabled = 0")).isEqualTo(1);
    }

    @Test
    void updateChangesThePassword() {
        accounts.createPlayer("Ada", true);
        accounts.updatePlayer("ada", null, true, "Another@123");
        assertThat(encoder.matches("Another@123", passwordOf("ada"))).isTrue();
    }

    @Test
    void staffAccountsAreNotPlayers() {
        assertThatThrownBy(() -> accounts.updatePlayer("ref", null, false, null)).hasMessage("Player not found");
        assertThatThrownBy(() -> accounts.updatePlayer("admin", null, false, null)).hasMessage("Player not found");
        assertThatThrownBy(() -> accounts.deletePlayer("ref")).hasMessage("Player not found");
    }

    @Test
    void deletesAPlayerWithNoHistory() {
        accounts.createPlayer("Ada", true);
        accounts.deletePlayer("ada");
        assertThat(count("SELECT count(*) FROM users WHERE username = 'ada'")).isZero();
        assertThat(count("SELECT count(*) FROM authorities WHERE username = 'ada'")).isZero();
    }

    @Test
    void keepsPlayersWhoHavePaid() {
        accounts.createPlayer("Ada", true);
        benefits.updateSettings(50000, 0, "Refreshment");
        benefits.confirmPayment("ada", "UPI-1", 50000);

        assertThatThrownBy(() -> accounts.deletePlayer("ada"))
                .hasMessage("Player has confirmed payments and cannot be deleted; disable the account instead");
    }

    @Test
    void keepsPlayersPlacedInADraw() {
        accounts.createPlayer("Ada", true);
        jdbc.update("INSERT INTO events(id, name, contestant_count) VALUES ('e', 'Event', 2)");
        jdbc.update("INSERT INTO draw_slots(event_id, slot, player_username) VALUES ('e', 1, 'ada')");

        assertThatThrownBy(() -> accounts.deletePlayer("ada"))
                .hasMessage("Player is placed in a draw; remove them from the draw first");
    }

    @Test
    void isAdminOnly() {
        signIn("ref", "OFFICIAL");
        assertThatThrownBy(() -> accounts.createPlayer("Ada", true))
                .isInstanceOf(AccessDeniedException.class);
    }
}
