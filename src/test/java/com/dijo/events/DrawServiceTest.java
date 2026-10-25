package com.dijo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DrawServiceTest extends DatabaseTest {
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
        signedUpPlayer("ada", "bob", "cy");
        signIn("admin", "ADMIN", "USER");
        event = events.createEvent("Singles", 4);
    }

    private void fill() {
        draws.assign(event, 1, "ada");
        draws.assign(event, 2, "bob");
        draws.assign(event, 3, "cy");
        draws.assignBye(event, 4);
    }

    @Test
    void placesSignedUpPlayers() {
        draws.assign(event, 1, "ada");

        assertThat(draws.draw(event).getFirst().playerUsername()).isEqualTo("ada");
        assertThat(draws.unplacedPlayers(event)).extracting(Player::username).containsExactly("bob", "cy");
    }

    @Test
    void showsPlayersByFullName() {
        jdbc.update("UPDATE users SET full_name = 'Dijo T Mathews' WHERE username = 'cy'");
        draws.assign(event, 1, "cy");
        draws.assign(event, 2, "bob");

        assertThat(draws.draw(event).getFirst().playerName()).isEqualTo("Dijo T Mathews");
        assertThat(draws.unplacedPlayers(event)).containsExactly(new Player("ada", "ada"));
        draws.assignBye(event, 3);
        draws.assignBye(event, 4);
        draws.lock(event);
        Match first = matches.bracket(event).getFirst();
        assertThat(first.player1()).isEqualTo("cy");
        assertThat(first.side1()).isEqualTo("Dijo T Mathews");
        assertThat(first.side2()).isEqualTo("bob");
    }

    @Test
    void onlySignedUpEnabledPlayersCanBePlaced() {
        player("dee");
        account("ref", "OFFICIAL");
        jdbc.update("UPDATE users SET enabled = 0 WHERE username = 'cy'");

        for (String notEligible : new String[] {"dee", "ref", "cy", "admin"}) {
            assertThatThrownBy(() -> draws.assign(event, 1, notEligible))
                    .hasMessage("Only signed-up players can be placed in the draw");
        }
        assertThat(draws.unplacedPlayers(event)).extracting(Player::username).containsExactly("ada", "bob");
    }

    @Test
    void aPlayerHoldsAtMostOneSlotPerEvent() {
        draws.assign(event, 1, "ada");
        assertThatThrownBy(() -> draws.assign(event, 3, "ada")).hasMessage("ada is already in slot 1");

        String other = events.createEvent("Doubles", 4);
        draws.assign(other, 3, "ada");
        assertThat(draws.draw(other).get(2).playerUsername()).isEqualTo("ada");
    }

    @Test
    void placingReplacesWhoeverWasInTheSlot() {
        draws.assignBye(event, 1);
        draws.assign(event, 1, "ada");

        DrawSlot slot = draws.draw(event).getFirst();
        assertThat(slot.playerUsername()).isEqualTo("ada");
        assertThat(slot.bye()).isFalse();
    }

    @Test
    void aDrawMayHoldAnyNumberOfByes() {
        draws.assign(event, 1, "ada");
        draws.assignBye(event, 2);
        draws.assignBye(event, 3);
        draws.assignBye(event, 4);

        assertThat(draws.draw(event)).filteredOn(DrawSlot::bye).hasSize(3);
    }

    @Test
    void lockingNeedsAtLeastTwoPlayers() {
        draws.assign(event, 1, "ada");
        draws.assignBye(event, 2);
        draws.assignBye(event, 3);
        draws.assignBye(event, 4);
        assertThatThrownBy(() -> draws.lock(event))
                .hasMessage("Place at least two players before locking the draw");

        draws.assign(event, 3, "bob");
        draws.lock(event);
        assertThat(events.event(event).orElseThrow().drawLocked()).isTrue();
    }

    @Test
    void slotsCanBeClearedOneByOneOrAllAtOnce() {
        fill();
        draws.clearSlot(event, 1);
        assertThat(draws.draw(event).getFirst().empty()).isTrue();

        draws.clearDraw(event);
        assertThat(draws.draw(event)).allMatch(DrawSlot::empty);
    }

    @Test
    void lockingNeedsEverySlotFilled() {
        draws.assign(event, 1, "ada");
        assertThatThrownBy(() -> draws.lock(event))
                .hasMessage("3 slots are still empty; fill them with players or BYEs");
        draws.assign(event, 2, "bob");
        draws.assign(event, 3, "cy");
        assertThatThrownBy(() -> draws.lock(event))
                .hasMessage("1 slot is still empty; fill it with a player or a BYE");
    }

    @Test
    void lockingCreatesTheKnockoutWithByesAdvancingAutomatically() {
        fill();
        draws.lock(event);

        assertThat(events.event(event).orElseThrow().drawLocked()).isTrue();
        var all = matches.matches(event);
        assertThat(all).hasSize(3);

        Match first = all.get(0);
        assertThat(first.roundName()).isEqualTo("Semi-final");
        assertThat(first.side1()).isEqualTo("ada");
        assertThat(first.side2()).isEqualTo("bob");
        assertThat(first.status()).isEqualTo(Match.Status.SCHEDULED);

        Match bye = all.get(1);
        assertThat(bye.byeMatch()).isTrue();
        assertThat(bye.winner()).isEqualTo("cy");
        assertThat(bye.status()).isEqualTo(Match.Status.AWAITING_CONFIRMATION);

        Match fin = all.get(2);
        assertThat(fin.roundName()).isEqualTo("Final");
        assertThat(fin.status()).isEqualTo(Match.Status.WAITING_FOR_PLAYERS);
    }

    @Test
    void aLockedDrawCannotChange() {
        fill();
        draws.lock(event);

        assertThatThrownBy(() -> draws.assign(event, 1, "bob"))
                .hasMessage("The draw is locked; unlock it to make changes");
        assertThatThrownBy(() -> draws.clearDraw(event))
                .hasMessage("The draw is locked; unlock it to make changes");
        assertThatThrownBy(() -> draws.lock(event))
                .hasMessage("Event not found or already locked");
    }

    @Test
    void unlockingRemovesTheMatches() {
        fill();
        draws.lock(event);
        draws.unlock(event);

        assertThat(events.event(event).orElseThrow().drawLocked()).isFalse();
        assertThat(matches.matches(event)).isEmpty();
        draws.clearSlot(event, 1);
    }

    @Test
    void unlockingIsBlockedOnceAResultIsEntered() {
        fill();
        draws.lock(event);
        matches.recordResult(event, 1, 1, "ada", "21-10");

        assertThatThrownBy(() -> draws.unlock(event))
                .hasMessage("Match results have been entered; the draw can no longer be unlocked");
    }

    @Test
    void playersSeeTheirSlotAndFirstOpponent() {
        fill();
        signIn("cy", "USER");

        var mine = draws.myDraws();
        assertThat(mine).hasSize(1);
        PlayerDraw draw = mine.getFirst();
        assertThat(draw.slot()).isEqualTo(3);
        assertThat(draw.match()).isEqualTo(2);
        assertThat(draw.opponent()).isNull();
        assertThat(draw.opponentBye()).isTrue();
        assertThat(draw.drawLocked()).isFalse();
    }
}
