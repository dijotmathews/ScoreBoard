package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.events.DrawService;
import com.dijo.events.EventService;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Building and locking a draw as the admin, then scoring a match as its official. */
class DrawAndResultsUiTest extends UiTest {
    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    @Autowired
    MatchService matches;

    private String event;

    @BeforeEach
    void setUp() {
        data.account("admin", "ADMIN", "USER");
        data.account("ref", "OFFICIAL");
        data.signedUpPlayer("ada", "bob", "cy");
        event = asAdmin(() -> events.createEvent("Singles", 4));
    }

    private void place(int row, String choice) {
        @SuppressWarnings("unchecked")
        ComboBox<Object> picker = (ComboBox<Object>) test(find(Grid.class).single()).getCellComponent(row, 1);
        test(picker).selectItem(choice);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
    void adminFillsAndLocksTheDraw() {
        navigate(AdminDrawView.class, event);
        place(0, "ada");
        place(1, "bob");
        place(2, "cy");
        assertThat(notifications()).contains("ada placed in slot 1");

        click("Lock draw");
        assertThat(notifications()).anyMatch(n -> n.contains("1 slot is still empty"));

        place(3, "BYE");
        click("Lock draw");

        assertThat(notifications()).contains("Draw locked");
        assertThat(events.event(event).orElseThrow().drawLocked()).isTrue();
        assertThat(matches.matches(event)).hasSize(3);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
    void aMatchBetweenTwoByesIsNotListedAndSendsAByeThrough() {
        navigate(AdminDrawView.class, event);
        place(0, "ada");
        place(1, "bob");
        place(2, "BYE");
        place(3, "BYE");
        click("Lock draw");
        assertThat(notifications()).contains("Draw locked");

        navigate(AdminMatchesView.class, event);
        var grid = test(find(Grid.class).single());
        assertThat(grid.size()).isEqualTo(2);
        assertThat(grid.getCellText(0, 1)).isEqualTo("ada vs bob");
        assertThat(grid.getCellText(1, 1)).isEqualTo("TBD vs BYE");

        matches.recordResult(event, 1, 1, "bob", "21-12");
        matches.confirmResult(event, 1, 1);
        navigate(AdminEventsView.class);
        navigate(AdminMatchesView.class, event);
        grid = test(find(Grid.class).single());
        assertThat(grid.getCellText(1, 1)).isEqualTo("bob vs BYE");
        assertThat(grid.getCellText(1, 5)).isEqualTo("bob");
    }

    @Test
    @WithMockUser(username = "ref", roles = "OFFICIAL")
    void officialEntersAResultForTheAdminToConfirm() {
        asAdmin(() -> {
            draws.assign(event, 1, "ada");
            draws.assign(event, 2, "bob");
            draws.assign(event, 3, "cy");
            draws.assignBye(event, 4);
            draws.lock(event);
            matches.assignOfficial(event, 1, 1, "ref");
        });

        navigate(OfficialHomeView.class);
        click("Enter result");
        test(find(RadioButtonGroup.class).single()).selectItem("ada");
        test(find(TextField.class).withLabel("Score").single()).setValue("21-15, 21-19");
        click("Save result");

        assertThat(notifications()).contains("Result saved; awaiting the admin's confirmation");
        var semi = asAdmin(() -> matches.currentMatch(event, 1, 1).orElseThrow());
        assertThat(semi.winner()).isEqualTo("ada");
        assertThat(semi.resultBy()).isEqualTo("ref");
        assertThat(semi.confirmed()).isFalse();
    }
}
