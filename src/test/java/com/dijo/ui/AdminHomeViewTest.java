package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.events.DrawService;
import com.dijo.events.EventService;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Admin Home lists the matches still to be played, one card per event. */
@WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
class AdminHomeViewTest extends UiTest {
    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    @Autowired
    MatchService matches;

    @BeforeEach
    void setUp() {
        data.account("admin", "ADMIN", "USER");
        data.signedUpPlayer("ada", "bob", "cy", "dee");
    }

    private String lockedDraw(String name, String... players) {
        String event = events.createEvent(name, players.length);
        for (int i = 0; i < players.length; i++) draws.assign(event, i + 1, players[i]);
        draws.lock(event);
        return event;
    }

    /** Each card's rows as their text (players, round and match, then the badge), in order. */
    private List<String> rows(Card card) {
        return card.getChildren()
                .flatMap(c -> c.getElement().getChildren().map(e -> e.getComponent().orElse(null)))
                .filter(c -> c instanceof Div && c.hasClassName("list-row"))
                .map(c -> c.getElement().getTextRecursively())
                .toList();
    }

    @Test
    void showsRemainingMatchesByEventWithScheduledOnesFirst() {
        String singles = lockedDraw("Singles", "ada", "bob", "cy", "dee");
        lockedDraw("Doubles", "ada", "bob");
        matches.schedule(singles, 1, 2, "2", LocalTime.of(10, 30));

        navigate(AdminHomeView.class);

        List<Card> cards = find(Card.class).all().stream()
                .filter(c -> c.getParent().filter(p -> p.hasClassName("home-remaining")).isPresent())
                .toList();
        assertThat(cards).hasSize(2);
        assertThat(rows(cards.get(0))).containsExactly("ada vs bobFinal · Match 1Not scheduled");
        assertThat(rows(cards.get(1))).containsExactly(
                "cy vs deeSemi-final · Match 2Court 2 · 10:30 AM",
                "ada vs bobSemi-final · Match 1Not scheduled",
                "TBD vs TBDFinal · Match 1Not scheduled");
    }

    @Test
    void playedMatchesAreLeftOut() {
        String doubles = lockedDraw("Doubles", "ada", "bob");
        matches.recordResult(doubles, 1, 1, "ada", "21-15");
        matches.confirmResult(doubles, 1, 1);

        navigate(AdminHomeView.class);

        assertThat(find(Card.class).all()).noneMatch(c -> c.getParent()
                .filter(p -> p.hasClassName("home-remaining")).isPresent());
        assertThat(find(com.vaadin.flow.component.html.Span.class)
                .withText("No matches to play. Lock an event's draw to create its matches.").exists()).isTrue();
    }
}
