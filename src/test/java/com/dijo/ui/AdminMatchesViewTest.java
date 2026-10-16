package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.events.DrawService;
import com.dijo.events.EventService;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Semi-final 1: ada v bob; semi-final 2: cy v BYE; then the final. */
@WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
class AdminMatchesViewTest extends UiTest {
    private static final int OFFICIAL = 2, COURT = 3, TIME = 4, ACTION = 7;

    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    @Autowired
    MatchService matches;

    private String event;

    @BeforeEach
    void lockedDraw() {
        data.account("admin", "ADMIN", "USER");
        data.account("ref", "OFFICIAL");
        data.signedUpPlayer("ada", "bob", "cy");
        event = events.createEvent("Singles", 4);
        draws.assign(event, 1, "ada");
        draws.assign(event, 2, "bob");
        draws.assign(event, 3, "cy");
        draws.assignBye(event, 4);
        draws.lock(event);
        navigate(AdminMatchesView.class, event);
    }

    @SuppressWarnings("unchecked")
    private Grid<Match> grid() {
        return find(Grid.class).single();
    }

    private Component cell(int row, int column) {
        return test(grid()).getCellComponent(row, column);
    }

    private void enter(int row, String court, LocalTime time) {
        if (court != null) test((TextField) cell(row, COURT)).setValue(court);
        if (time != null) test((TimePicker) cell(row, TIME)).setValue(time);
    }

    @Test
    void listsEveryMatchInRoundOrder() {
        var grid = test(grid());
        assertThat(grid.size()).isEqualTo(3);
        assertThat(grid.getCellText(0, 0)).isEqualTo("Semi-final · 1");
        assertThat(grid.getCellText(0, 1)).isEqualTo("ada vs bob");
        assertThat(grid.getCellText(1, 1)).isEqualTo("cy vs BYE");
        assertThat(grid.getCellText(2, 1)).isEqualTo("TBD vs TBD");
    }

    @Test
    void assigningAnOfficialSavesImmediately() {
        @SuppressWarnings("unchecked")
        ComboBox<String> picker = (ComboBox<String>) cell(0, OFFICIAL);
        test(picker).selectItem("ref");

        assertThat(matches.currentMatch(event, 1, 1).orElseThrow().official()).isEqualTo("ref");
    }

    @Test
    void byeMatchesHaveNoCourtOrTime() {
        assertThat(cell(1, COURT)).isInstanceOf(Span.class);
        assertThat(cell(1, TIME)).isInstanceOf(Span.class);
    }

    @Test
    void sharesAMessageWithTheCourtsAndTimesInTimeOrder() {
        enter(2, null, LocalTime.of(16, 0));
        enter(0, "2", LocalTime.of(10, 30));

        click("Share schedule");

        assertThat(find(TextArea.class).single().getValue()).isEqualTo("""
                *Singles – Match schedule*

                Semi-final 1: ada vs bob – Court 2 · 10:30 AM
                Final 1: TBD vs TBD – 4:00 PM""");
    }

    @Test
    void aCourtWithoutATimeIsStillShared() {
        enter(0, " 5 ", null);
        click("Share schedule");
        assertThat(find(TextArea.class).single().getValue()).endsWith("Semi-final 1: ada vs bob – Court 5");
    }

    @Test
    void withNothingEnteredOnlyTheDrawImageIsOffered() {
        click("Share schedule");

        TabSheet tabs = find(TabSheet.class).last();
        assertThat(test(tabs).getTab("Image")).isNotNull();
        assertThat(find(TextArea.class).exists()).isFalse();
        assertThat(find(Button.class).withText("Copy image").exists()).isTrue();
        assertThat(find(Button.class).withText("Download").exists()).isTrue();
    }

    @Test
    void courtsAndTimesSurviveARefreshButAreNeverSaved() {
        enter(0, "2", LocalTime.of(10, 30));

        // Changing the filter rebuilds every row from the in-memory values.
        @SuppressWarnings("unchecked")
        Select<Object> show = find(Select.class).single();
        test(show).selectItem("To play");
        test(show).selectItem("All matches");
        assertThat(((TextField) cell(0, COURT)).getValue()).isEqualTo("2");

        // Leaving and coming back builds a fresh page, which starts empty: nothing was stored.
        navigate(AdminEventsView.class);
        navigate(AdminMatchesView.class, event);
        assertThat(((TextField) cell(0, COURT)).getValue()).isEmpty();
        assertThat(((TimePicker) cell(0, TIME)).getValue()).isNull();
    }

    @Test
    void confirmingAByeFromTheReviewDialogAdvancesThePlayer() {
        test((Button) cell(1, ACTION)).click();
        click("Confirm");

        assertThat(notifications()).contains("Result confirmed");
        assertThat(test(grid()).getCellText(2, 1)).isEqualTo("TBD vs cy");
    }

    @Test
    void enteringAndConfirmingAResultInOneStep() {
        test((Button) cell(0, ACTION)).click();
        test(find(com.vaadin.flow.component.radiobutton.RadioButtonGroup.class).single()).selectItem("bob");
        test(find(TextField.class).withLabel("Score").single()).setValue("21-17");
        click("Save & confirm");

        Match semi = matches.currentMatch(event, 1, 1).orElseThrow();
        assertThat(semi.winner()).isEqualTo("bob");
        assertThat(semi.score()).isEqualTo("21-17");
        assertThat(semi.confirmed()).isTrue();
    }
}
