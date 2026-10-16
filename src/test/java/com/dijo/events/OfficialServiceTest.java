package com.dijo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import com.dijo.events.OfficialService.Official;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfficialServiceTest extends DatabaseTest {
    @Autowired
    OfficialService officials;

    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    @Autowired
    MatchService matches;

    @BeforeEach
    void setUp() {
        account("admin", "ADMIN", "USER");
        signIn("admin", "ADMIN", "USER");
    }

    private String lockedFourPlayerEvent() {
        signedUpPlayer("ada", "bob", "cy", "dee");
        String event = events.createEvent("Singles", 4);
        draws.assign(event, 1, "ada");
        draws.assign(event, 2, "bob");
        draws.assign(event, 3, "cy");
        draws.assign(event, 4, "dee");
        draws.lock(event);
        return event;
    }

    @Test
    void createsAnOfficialAccount() {
        officials.createOfficial("ref", "Secret@123", true);

        assertThat(officials.officials()).containsExactly(new Official("ref", true, 0, 0, 0));
        assertThat(count("SELECT count(*) FROM authorities WHERE username = 'ref' AND authority = 'ROLE_OFFICIAL'"))
                .isEqualTo(1);
    }

    @Test
    void sharesTheAccountRulesWithPlayers() {
        officials.createOfficial("ref", "Secret@123", true);
        assertThatThrownBy(() -> officials.createOfficial("ref", "Secret@123", true))
                .hasMessage("Username already exists");
        assertThatThrownBy(() -> officials.createOfficial("x", "Secret@123", true))
                .hasMessage("Username must be 3-32 letters, digits, '.', '_' or '-'");
        assertThatThrownBy(() -> officials.createOfficial("ump", "short", true))
                .hasMessage("Password must be at least 8 characters");
    }

    @Test
    void updatesEnabledAndPassword() {
        officials.createOfficial("ref", "Secret@123", true);
        officials.updateOfficial("ref", false, "Another@123");

        assertThat(officials.officials().getFirst().enabled()).isFalse();
        String hash = jdbc.queryForObject("SELECT password FROM users WHERE username = 'ref'", String.class);
        assertThat(encoder.matches("Another@123", hash)).isTrue();
    }

    @Test
    void playersAndAdminsAreNotOfficials() {
        player("ada");
        assertThatThrownBy(() -> officials.updateOfficial("ada", false, null)).hasMessage("Official not found");
        assertThatThrownBy(() -> officials.deleteOfficial("admin")).hasMessage("Official not found");
    }

    @Test
    void countsWorkloadAndUnassignedMatches() {
        officials.createOfficial("ref", "Secret@123", true);
        String event = lockedFourPlayerEvent();
        assertThat(officials.unassignedMatches()).isEqualTo(3);

        matches.assignOfficial(event, 1, 1, "ref");
        matches.assignOfficial(event, 1, 2, "ref");
        matches.recordResult(event, 1, 1, "ada", null);
        matches.confirmResult(event, 1, 1);

        assertThat(officials.officials()).containsExactly(new Official("ref", true, 2, 1, 1));
        assertThat(officials.unassignedMatches()).isEqualTo(1);
    }

    @Test
    void deletesAnOfficialWithNoHistory() {
        officials.createOfficial("ref", "Secret@123", true);
        officials.deleteOfficial("ref");
        assertThat(officials.officials()).isEmpty();
    }

    @Test
    void keepsAnOfficialWithMatches() {
        officials.createOfficial("ref", "Secret@123", true);
        String event = lockedFourPlayerEvent();
        matches.assignOfficial(event, 1, 1, "ref");

        assertThatThrownBy(() -> officials.deleteOfficial("ref"))
                .hasMessageStartingWith("This official has matches or records against their name");
    }

    @Test
    void keepsAnOfficialWhoRecordedAPayment() {
        officials.createOfficial("ref", "Secret@123", true);
        player("ada");
        jdbc.update("""
                INSERT INTO entry_payments(id, player_username, payment_reference, amount_paise,
                    confirmed_by, confirmed_at) VALUES ('p', 'ada', 'UPI-1', 50000, 'ref', '2026-10-01T10:00:00Z')
                """);

        assertThatThrownBy(() -> officials.deleteOfficial("ref"))
                .hasMessageStartingWith("This official has matches or records against their name");
    }
}
