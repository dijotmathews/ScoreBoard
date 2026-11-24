package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.events.DrawService;
import com.dijo.events.EventService;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
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

    /** Each card's rows as their text (round, sides, court/time, official, buttons), in order. */
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
        assertThat(rows(cards.get(0))).containsExactly("Final · Match 1adabobNo court or timeNo officialScheduleResult");
        assertThat(rows(cards.get(1))).containsExactly(
                "Semi-final · Match 2cydeeCourt 2 · 10:30 AMNo officialScheduleResult",
                "Semi-final · Match 1adabobNo court or timeNo officialScheduleResult",
                "Final · Match 1TBDTBDNo court or timeNo officialScheduleResult");
        // The final's players are not known yet, so its result cannot be entered.
        assertThat(find(Button.class).withText("Result").all().stream().map(Button::isEnabled))
                .containsExactly(true, true, true, false);
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

    @Test
    void resultButtonEntersAndConfirmsTheResult() {
        String doubles = lockedDraw("Doubles", "ada", "bob");
        navigate(AdminHomeView.class);

        click("Result");
        test(find(RadioButtonGroup.class).single()).selectItem("bob");
        test(find(TextField.class).withLabel("Score").single()).setValue("21-17");
        click("Save & confirm");

        Match fin = matches.currentMatch(doubles, 1, 1).orElseThrow();
        assertThat(fin.winner()).isEqualTo("bob");
        assertThat(fin.score()).isEqualTo("21-17");
        assertThat(fin.confirmed()).isTrue();
        assertThat(notifications()).contains("Result confirmed");
        // The played match drops off the list.
        assertThat(find(com.vaadin.flow.component.html.Span.class)
                .withText("No matches to play. Lock an event's draw to create its matches.").exists()).isTrue();
    }

    @Test
    void flagsEnteredResultsToConfirmFirst() {
        String singles = lockedDraw("Singles", "ada", "bob", "cy", "dee");
        matches.recordResult(singles, 1, 2, "dee", "21-19");
        navigate(AdminHomeView.class);

        Card card = find(Card.class).all().stream()
                .filter(c -> c.getParent().filter(p -> p.hasClassName("home-remaining")).isPresent())
                .findFirst().orElseThrow();
        assertThat(card.getElement().getTextRecursively()).contains("1 result to confirm");
        assertThat(rows(card)).containsExactly(
                "Semi-final · Match 2Result to confirmcydee21-19No court or timeNo officialEntered by adminScheduleReview & confirm",
                "Semi-final · Match 1adabobNo court or timeNo officialScheduleResult",
                "Final · Match 1TBDTBDNo court or timeNo officialScheduleResult");

        click("Review & confirm");
        click("Confirm");

        assertThat(matches.currentMatch(singles, 1, 2).orElseThrow().confirmed()).isTrue();
        assertThat(notifications()).contains("Result confirmed");
        // The confirmed semi-final drops off; dee goes through to the final.
        assertThat(visibleRows()).containsExactly(
                "Semi-final · Match 1adabobNo court or timeNo officialScheduleResult",
                "Final · Match 1TBDdeeNo court or timeNo officialScheduleResult");
        assertThat(find(Button.class).withText("Review & confirm").exists()).isFalse();
    }

    @Test
    void scheduleButtonSetsCourtTimeAndOfficial() {
        data.account("olly", "OFFICIAL");
        String doubles = lockedDraw("Doubles", "ada", "bob");
        navigate(AdminHomeView.class);

        click("Schedule");
        test(find(TextField.class).withLabel("Court").single()).setValue("3");
        test(find(TimePicker.class).withLabel("Time").single()).setValue(LocalTime.of(11, 15));
        test(find(ComboBox.class).withLabel("Official").single()).selectItem("olly");
        click("Save");

        Match fin = matches.currentMatch(doubles, 1, 1).orElseThrow();
        assertThat(fin.court()).isEqualTo("3");
        assertThat(fin.startTime()).isEqualTo(LocalTime.of(11, 15));
        assertThat(fin.official()).isEqualTo("olly");
        assertThat(notifications()).contains("Match updated");
        assertThat(find(Div.class).all().stream().filter(d -> d.hasClassName("list-row"))
                .map(d -> d.getElement().getTextRecursively()))
                .containsExactly("Final · Match 1adabobCourt 3 · 11:15 AMollyScheduleResult");

        // Clearing them all unschedules the match.
        click("Schedule");
        test(find(TextField.class).withLabel("Court").single()).setValue("");
        test(find(TimePicker.class).withLabel("Time").single()).setValue(null);
        test(find(ComboBox.class).withLabel("Official").single()).clickClearButton();
        click("Save");
        fin = matches.currentMatch(doubles, 1, 1).orElseThrow();
        assertThat(fin.scheduled()).isFalse();
        assertThat(fin.official()).isNull();
    }

    private List<String> visibleRows() {
        return find(Div.class).all().stream().filter(d -> d.hasClassName("list-row"))
                .map(d -> d.getElement().getTextRecursively()).toList();
    }

    private void search(String term) {
        test(find(TextField.class).all().stream()
                .filter(t -> "Search by player or event".equals(t.getPlaceholder())).findFirst().orElseThrow())
                .setValue(term);
    }

    @Test
    void searchesRemainingMatchesByPlayerOrEvent() {
        lockedDraw("Singles", "ada", "bob", "cy", "dee");
        lockedDraw("Doubles", "ada", "bob");
        navigate(AdminHomeView.class);
        assertThat(visibleRows()).hasSize(4);

        // By player, case-insensitively: Ada plays in both events.
        search("ADA");
        assertThat(visibleRows()).containsExactly(
                "Final · Match 1adabobNo court or timeNo officialScheduleResult",
                "Semi-final · Match 1adabobNo court or timeNo officialScheduleResult");
        assertThat(find(com.vaadin.flow.component.html.Span.class).withText("Showing 2 of 4 matches").exists())
                .isTrue();

        // By event name, and by both: every word must match.
        search("singles");
        assertThat(visibleRows()).hasSize(3);
        search("dee singles");
        assertThat(visibleRows()).containsExactly("Semi-final · Match 2cydeeNo court or timeNo officialScheduleResult");

        search("zed");
        assertThat(visibleRows()).isEmpty();
        assertThat(find(com.vaadin.flow.component.html.Span.class)
                .withText("No remaining matches for \"zed\".").exists()).isTrue();

        // The search is kept when the list reloads after an update.
        search("cy");
        click("Schedule");
        test(find(TextField.class).withLabel("Court").single()).setValue("4");
        click("Save");
        assertThat(visibleRows()).containsExactly("Semi-final · Match 2cydeeCourt 4No officialScheduleResult");
    }
}
