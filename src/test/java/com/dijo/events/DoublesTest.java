package com.dijo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A four-slot doubles draw: teams led by ada and cy, partnered by bob and dee. */
class DoublesTest extends DatabaseTest {
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
        signedUpPlayer("ada", "bob", "cy", "dee", "eve");
        player("zed");
        signIn("admin", "ADMIN", "USER");
        event = events.createEvent("Doubles", 4, true);
    }

    private void teams() {
        draws.assign(event, 1, "ada");
        draws.assignPartner(event, 1, "bob");
        draws.assign(event, 2, "cy");
        draws.assignPartner(event, 2, "dee");
        draws.assignBye(event, 3);
        draws.assignBye(event, 4);
    }

    @Test
    void placesTeams() {
        jdbc.update("UPDATE users SET full_name = 'Dijo T Mathews' WHERE username = 'bob'");
        teams();

        DrawSlot first = draws.draw(event).getFirst();
        assertThat(first.playerUsername()).isEqualTo("ada");
        assertThat(first.partnerUsername()).isEqualTo("bob");
        assertThat(first.teamName()).isEqualTo("ada & Dijo T Mathews");
        assertThat(draws.unplacedPlayers(event)).extracting(Player::username).containsExactly("eve");
    }

    @Test
    void partnersMustBeSignedUpDistinctAndNotPlaced() {
        draws.assign(event, 1, "ada");
        draws.assign(event, 2, "cy");
        draws.assignPartner(event, 2, "dee");

        assertThatThrownBy(() -> draws.assignPartner(event, 1, "zed"))
                .hasMessage("Only signed-up players can be placed in the draw");
        assertThatThrownBy(() -> draws.assignPartner(event, 1, "ada"))
                .hasMessage("A player cannot partner themselves");
        assertThatThrownBy(() -> draws.assignPartner(event, 1, "cy")).hasMessage("cy is already in slot 2");
        assertThatThrownBy(() -> draws.assignPartner(event, 1, "dee")).hasMessage("dee is already in slot 2");
        assertThatThrownBy(() -> draws.assign(event, 3, "dee")).hasMessage("dee is already in slot 2");
        assertThatThrownBy(() -> draws.assignPartner(event, 3, "eve"))
                .hasMessage("Place a player in the slot first");
    }

    @Test
    void partnersOnlyInDoublesEvents() {
        String singles = events.createEvent("Singles", 2);
        draws.assign(singles, 1, "ada");
        assertThatThrownBy(() -> draws.assignPartner(singles, 1, "bob"))
                .hasMessage("Partners can only be placed in a doubles event");
    }

    @Test
    void replacingThePlayerKeepsThePartnerUnlessTheyAreTheNewPlayer() {
        draws.assign(event, 1, "ada");
        draws.assignPartner(event, 1, "bob");

        draws.assign(event, 1, "eve");
        assertThat(draws.draw(event).getFirst().partnerUsername()).isEqualTo("bob");

        draws.assign(event, 1, "bob");
        assertThat(draws.draw(event).getFirst().partnerUsername()).isNull();
    }

    @Test
    void clearingOrAByeRemovesThePartner() {
        teams();
        draws.clearPartner(event, 2);
        assertThat(draws.draw(event).get(1).partnerUsername()).isNull();

        draws.assignBye(event, 1);
        assertThat(draws.draw(event).getFirst().partnerUsername()).isNull();
        draws.assign(event, 1, "ada");
        draws.assignPartner(event, 1, "bob");
        draws.clearSlot(event, 1);
        assertThat(draws.draw(event).getFirst().partnerUsername()).isNull();
    }

    @Test
    void lockingNeedsAPartnerForEveryPlayer() {
        teams();
        draws.clearPartner(event, 1);
        draws.clearPartner(event, 2);

        assertThatThrownBy(() -> draws.lock(event)).hasMessage("2 teams are missing a partner");
        draws.assignPartner(event, 1, "bob");
        assertThatThrownBy(() -> draws.lock(event)).hasMessage("1 team is missing a partner");
    }

    @Test
    void matchesShowTeamsAndAdvanceTheTeam() {
        teams();
        draws.lock(event);

        Match semi = matches.currentMatch(event, 1, 1).orElseThrow();
        assertThat(semi.side1()).isEqualTo("ada & bob");
        assertThat(semi.side2()).isEqualTo("cy & dee");

        matches.recordResult(event, 1, 1, "cy", "21-18");
        matches.confirmResult(event, 1, 1);
        assertThat(matches.currentMatch(event, 1, 1).orElseThrow().winnerName()).isEqualTo("cy & dee");
        assertThat(matches.currentMatch(event, 2, 1).orElseThrow().side1()).isEqualTo("cy & dee");
    }

    @Test
    void partnersSeeTheirTeamsDrawsAndMatches() {
        teams();
        draws.lock(event);
        signIn("dee", "USER");

        PlayerDraw draw = draws.myDraws().getFirst();
        assertThat(draw.slot()).isEqualTo(2);
        assertThat(draw.opponentName()).isEqualTo("ada & bob");

        assertThat(matches.myPlayerMatches()).singleElement()
                .satisfies(m -> assertThat(m.teamOf("dee")).isEqualTo("cy"));

        // Once the team wins, the final is theirs too.
        signIn("admin", "ADMIN", "USER");
        matches.recordResult(event, 1, 1, "cy", "21-18");
        matches.confirmResult(event, 1, 1);
        signIn("dee", "USER");
        assertThat(matches.myPlayerMatches()).extracting(Match::round).containsExactly(1, 2);
    }

    @Test
    void theEventTypeStaysWhenTheEventIsUpdated() {
        events.updateEvent(event, "Mixed doubles", 8);
        assertThat(events.event(event).orElseThrow().doubles()).isTrue();
        assertThat(events.event(events.createEvent("Singles", 2)).orElseThrow().doubles()).isFalse();
    }
}
