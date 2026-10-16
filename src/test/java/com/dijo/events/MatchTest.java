package com.dijo.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MatchTest {
    private static Match match(int totalRounds, int round, String p1, boolean bye1, String p2, boolean bye2,
            String winner, String confirmedBy) {
        return new Match("e", "Singles", totalRounds, round, 1, p1, bye1, p2, bye2,
                null, winner, null, null, confirmedBy);
    }

    @Test
    void waitsUntilBothSidesAreKnown() {
        Match m = match(3, 2, "ada", false, null, false, null, null);
        assertThat(m.ready()).isFalse();
        assertThat(m.status()).isEqualTo(Match.Status.WAITING_FOR_PLAYERS);
        assertThat(m.side2()).isEqualTo("TBD");
    }

    @Test
    void isScheduledWithTwoPlayers() {
        Match m = match(3, 1, "ada", false, "bob", false, null, null);
        assertThat(m.status()).isEqualTo(Match.Status.SCHEDULED);
        assertThat(m.byeMatch()).isFalse();
    }

    @Test
    void awaitsConfirmationOnceAWinnerIsEntered() {
        Match m = match(3, 1, "ada", false, "bob", false, "ada", null);
        assertThat(m.status()).isEqualTo(Match.Status.AWAITING_CONFIRMATION);
    }

    @Test
    void isConfirmedOnceAnAdminConfirms() {
        Match m = match(3, 1, "ada", false, "bob", false, "ada", "admin");
        assertThat(m.confirmed()).isTrue();
        assertThat(m.status()).isEqualTo(Match.Status.CONFIRMED);
    }

    @Test
    void aPlayerAgainstAByeIsReady() {
        Match m = match(3, 1, null, true, "bob", false, "bob", null);
        assertThat(m.ready()).isTrue();
        assertThat(m.byeMatch()).isTrue();
        assertThat(m.side1()).isEqualTo("BYE");
    }

    @Test
    void twoByesMeanNoMatch() {
        Match m = match(3, 1, null, true, null, true, null, null);
        assertThat(m.noMatch()).isTrue();
        assertThat(m.status()).isEqualTo(Match.Status.NO_MATCH);
        assertThat(match(3, 1, "ada", false, null, true, "ada", null).noMatch()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"4, 4, Final", "4, 3, Semi-final", "4, 2, Quarter-final", "4, 1, Round 1", "5, 2, Round 2"})
    void namesRoundsFromTheFinalBackwards(int totalRounds, int round, String name) {
        assertThat(match(totalRounds, round, null, false, null, false, null, null).roundName()).isEqualTo(name);
    }
}
