package com.dijo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/** A four-player knockout: ada v bob and cy v dee in the semi-finals. */
class MatchServiceTest extends DatabaseTest {
    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    @Autowired
    MatchService matches;

    private String event;

    @BeforeEach
    void setUp() {
        account("admin", "ADMIN", "USER");
        account("ref", "OFFICIAL");
        account("ump", "OFFICIAL");
        signedUpPlayer("ada", "bob", "cy", "dee");
        signIn("admin", "ADMIN", "USER");
        event = events.createEvent("Singles", 4);
        draws.assign(event, 1, "ada");
        draws.assign(event, 2, "bob");
        draws.assign(event, 3, "cy");
        draws.assign(event, 4, "dee");
        draws.lock(event);
    }

    private Match match(int round, int matchNo) {
        return matches.currentMatch(event, round, matchNo).orElseThrow();
    }

    @Test
    void listsEnabledOfficials() {
        jdbc.update("UPDATE users SET enabled = 0 WHERE username = 'ump'");
        assertThat(matches.officials()).containsExactly("ref");
    }

    @Test
    void assignsAndRemovesAnOfficial() {
        matches.assignOfficial(event, 1, 1, "ref");
        assertThat(match(1, 1).official()).isEqualTo("ref");

        matches.assignOfficial(event, 1, 1, null);
        assertThat(match(1, 1).official()).isNull();
    }

    @Test
    void onlyEnabledOfficialsCanOfficiate() {
        assertThatThrownBy(() -> matches.assignOfficial(event, 1, 1, "ada"))
                .hasMessage("Only enabled officials can officiate a match");
    }

    @Test
    void anOfficialRecordsResultsOnlyForTheirOwnMatches() {
        matches.assignOfficial(event, 1, 1, "ref");

        as("ref", "OFFICIAL", () -> matches.recordResult(event, 1, 1, "ada", " 21-15, 21-19 "));
        assertThatThrownBy(() -> as("ref", "OFFICIAL",
                () -> matches.recordResult(event, 1, 2, "cy", null)))
                .hasMessage("You are not the official for this match");

        Match result = match(1, 1);
        assertThat(result.winner()).isEqualTo("ada");
        assertThat(result.score()).isEqualTo("21-15, 21-19");
        assertThat(result.resultBy()).isEqualTo("ref");
        assertThat(result.status()).isEqualTo(Match.Status.AWAITING_CONFIRMATION);
    }

    @Test
    void officialsSeeOnlyTheirMatches() {
        matches.assignOfficial(event, 1, 2, "ref");
        signIn("ref", "OFFICIAL");

        assertThat(matches.myMatches()).extracting(Match::matchNo).containsExactly(2);
        assertThat(matches.currentMatch(event, 1, 1)).isEmpty();
        assertThatThrownBy(() -> matches.matches(event)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theWinnerMustBeOneOfThePlayers() {
        assertThatThrownBy(() -> matches.recordResult(event, 1, 1, "cy", null))
                .hasMessage("Choose the winner");
        assertThatThrownBy(() -> matches.recordResult(event, 1, 1, null, null))
                .hasMessage("Choose the winner");
    }

    @Test
    void scoresAreLimitedTo100Characters() {
        assertThatThrownBy(() -> matches.recordResult(event, 1, 1, "ada", "1".repeat(101)))
                .hasMessage("Score must be at most 100 characters");
        matches.recordResult(event, 1, 1, "ada", "  ");
        assertThat(match(1, 1).score()).isNull();
    }

    @Test
    void laterRoundsWaitForTheirPlayers() {
        assertThatThrownBy(() -> matches.recordResult(event, 2, 1, "ada", null))
                .hasMessage("Both players are not known yet");
        assertThatThrownBy(() -> matches.confirmResult(event, 2, 1))
                .hasMessage("No result has been entered yet");
    }

    @Test
    void confirmingMovesTheWinnerIntoTheNextRound() {
        matches.recordResult(event, 1, 1, "ada", "21-10");
        matches.confirmResult(event, 1, 1);
        matches.recordResult(event, 1, 2, "dee", "21-18");
        matches.confirmResult(event, 1, 2);

        Match fin = match(2, 1);
        assertThat(fin.player1()).isEqualTo("ada");
        assertThat(fin.player2()).isEqualTo("dee");
        assertThat(fin.status()).isEqualTo(Match.Status.SCHEDULED);
        assertThat(match(1, 1).confirmedBy()).isEqualTo("admin");
        assertThat(matches.totals()).isEqualTo(new MatchService.MatchTotals(2, 3));
    }

    @Test
    void aConfirmedResultCannotBeChanged() {
        matches.recordResult(event, 1, 1, "ada", null);
        matches.confirmResult(event, 1, 1);

        assertThatThrownBy(() -> matches.recordResult(event, 1, 1, "bob", null))
                .hasMessage("The result is already confirmed");
        assertThatThrownBy(() -> matches.confirmResult(event, 1, 1))
                .hasMessage("The result is already confirmed");
        assertThatThrownBy(() -> matches.assignOfficial(event, 1, 1, "ref"))
                .hasMessage("The result is already confirmed");
    }

    @Test
    void reopeningTakesTheWinnerBackOutOfTheNextRound() {
        matches.recordResult(event, 1, 1, "ada", null);
        matches.confirmResult(event, 1, 1);
        matches.reopen(event, 1, 1);

        assertThat(match(1, 1).status()).isEqualTo(Match.Status.AWAITING_CONFIRMATION);
        assertThat(match(2, 1).player1()).isNull();
        assertThatThrownBy(() -> matches.reopen(event, 1, 1)).hasMessage("The result is not confirmed");
    }

    @Test
    void reopeningIsBlockedOnceTheNextMatchHasAResult() {
        matches.recordResult(event, 1, 1, "ada", null);
        matches.confirmResult(event, 1, 1);
        matches.recordResult(event, 1, 2, "cy", null);
        matches.confirmResult(event, 1, 2);
        matches.recordResult(event, 2, 1, "cy", null);

        assertThatThrownBy(() -> matches.reopen(event, 1, 1))
                .hasMessage("The next-round match already has a result");
    }

    @Test
    void confirmingTheFinalCrownsTheChampion() {
        matches.recordResult(event, 1, 1, "ada", null);
        matches.confirmResult(event, 1, 1);
        matches.recordResult(event, 1, 2, "cy", null);
        matches.confirmResult(event, 1, 2);
        matches.recordResult(event, 2, 1, "cy", "21-19");
        matches.confirmResult(event, 2, 1);

        Match fin = match(2, 1);
        assertThat(fin.confirmed()).isTrue();
        assertThat(fin.winner()).isEqualTo("cy");
        matches.reopen(event, 2, 1);
        assertThat(match(2, 1).confirmed()).isFalse();
    }

    @Test
    void onlyAnAdminConfirms() {
        matches.assignOfficial(event, 1, 1, "ref");
        as("ref", "OFFICIAL", () -> matches.recordResult(event, 1, 1, "ada", null));

        assertThatThrownBy(() -> as("ref", "OFFICIAL", () -> matches.confirmResult(event, 1, 1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void playersSeeTheBracketAndTheirOwnMatches() {
        signIn("ada", "USER");

        assertThat(matches.bracket(event)).hasSize(3);
        assertThat(matches.myPlayerMatches()).extracting(Match::matchNo).containsExactly(1);
    }

    @Test
    void theBracketIsHiddenUntilTheDrawIsLocked() {
        draws.unlock(event);
        signIn("ada", "USER");
        assertThat(matches.bracket(event)).isEmpty();
    }

    /**
     * Eight slots: ada v BYE, BYE v BYE, bob v cy, dee v BYE. The BYE v BYE match is
     * not played and sends a BYE into ada's next match.
     */
    private String drawWithManyByes() {
        String other = events.createEvent("Doubles", 8);
        draws.assign(other, 1, "ada");
        draws.assignBye(other, 2);
        draws.assignBye(other, 3);
        draws.assignBye(other, 4);
        draws.assign(other, 5, "bob");
        draws.assign(other, 6, "cy");
        draws.assign(other, 7, "dee");
        draws.assignBye(other, 8);
        draws.lock(other);
        return other;
    }

    @Test
    void aMatchBetweenTwoByesIsNotPlayedAndSendsAByeThrough() {
        String other = drawWithManyByes();

        Match noMatch = matches.currentMatch(other, 1, 2).orElseThrow();
        assertThat(noMatch.noMatch()).isTrue();
        assertThat(noMatch.status()).isEqualTo(Match.Status.NO_MATCH);

        Match next = matches.currentMatch(other, 2, 1).orElseThrow();
        assertThat(next.player1()).isNull();
        assertThat(next.player2Bye()).isTrue();
        assertThat(next.status()).isEqualTo(Match.Status.WAITING_FOR_PLAYERS);
        assertThatThrownBy(() -> matches.confirmResult(other, 1, 2))
                .hasMessage("No result has been entered yet");
    }

    @Test
    void aPlayerGoesThroughEveryByeInTurn() {
        String other = drawWithManyByes();

        matches.confirmResult(other, 1, 1);
        Match semi = matches.currentMatch(other, 2, 1).orElseThrow();
        assertThat(semi.player1()).isEqualTo("ada");
        assertThat(semi.winner()).isEqualTo("ada");
        assertThat(semi.resultBy()).isEqualTo("admin");
        assertThat(semi.status()).isEqualTo(Match.Status.AWAITING_CONFIRMATION);

        matches.confirmResult(other, 2, 1);
        assertThat(matches.currentMatch(other, 3, 1).orElseThrow().player1()).isEqualTo("ada");
    }

    @Test
    void reopeningAlsoUndoesGoingThroughTheNextBye() {
        String other = drawWithManyByes();
        matches.confirmResult(other, 1, 1);

        matches.reopen(other, 1, 1);

        Match semi = matches.currentMatch(other, 2, 1).orElseThrow();
        assertThat(semi.player1()).isNull();
        assertThat(semi.winner()).isNull();
        assertThat(semi.player2Bye()).isTrue();
    }

    @Test
    void reopeningIsBlockedOnceTheNextByeIsConfirmed() {
        String other = drawWithManyByes();
        matches.confirmResult(other, 1, 1);
        matches.confirmResult(other, 2, 1);

        assertThatThrownBy(() -> matches.reopen(other, 1, 1))
                .hasMessage("The next-round match already has a result");
    }

    @Test
    void matchesBetweenTwoByesAreNotCounted() {
        drawWithManyByes();
        // Singles: 3 matches. Doubles: 7, less the one between two BYEs.
        assertThat(matches.totals()).isEqualTo(new MatchService.MatchTotals(0, 9));
    }

    @Test
    void byeMatchesNeedOnlyConfirmation() {
        String other = events.createEvent("Doubles", 4);
        draws.assign(other, 1, "ada");
        draws.assignBye(other, 2);
        draws.assign(other, 3, "bob");
        draws.assign(other, 4, "cy");
        draws.lock(other);

        assertThatThrownBy(() -> matches.recordResult(other, 1, 1, "ada", null))
                .hasMessage("A BYE match needs only the admin's confirmation");
        matches.confirmResult(other, 1, 1);
        assertThat(matches.currentMatch(other, 2, 1).orElseThrow().player1()).isEqualTo("ada");
    }
}
