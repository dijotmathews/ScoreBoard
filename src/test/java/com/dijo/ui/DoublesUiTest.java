package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.events.DrawService;
import com.dijo.events.EventService;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Building a doubles draw as the admin: a player and a partner per slot. */
@WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
class DoublesUiTest extends UiTest {
    private static final int PLAYER = 1, PARTNER = 2;

    @Autowired
    EventService events;

    @Autowired
    DrawService draws;

    private String event;

    @BeforeEach
    void setUp() {
        data.account("admin", "ADMIN", "USER");
        data.signedUpPlayer("ada", "bob", "cy", "dee");
        event = events.createEvent("Doubles", 4, true);
    }

    @SuppressWarnings("unchecked")
    private Grid<Object> grid() {
        return find(Grid.class).single();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<Object> picker(int row, int column) {
        return (ComboBox<Object>) test(grid()).getCellComponent(row, column);
    }

    @Test
    void theAdminPlacesTeamsAndLocksTheDraw() {
        navigate(AdminDrawView.class, event);
        assertThat(grid().getColumns().get(PARTNER).isVisible()).isTrue();
        assertThat(picker(0, PARTNER).isEnabled()).isFalse();  // no player yet

        test(picker(0, PLAYER)).selectItem("ada");
        test(picker(0, PARTNER)).selectItem("bob");
        assertThat(notifications()).contains("bob partners ada");
        test(picker(1, PLAYER)).selectItem("cy");
        test(picker(2, PLAYER)).selectItem("BYE");
        test(picker(3, PLAYER)).selectItem("BYE");

        click("Lock draw");
        assertThat(notifications()).contains("1 team is missing a partner");

        test(picker(1, PARTNER)).selectItem("dee");
        click("Lock draw");
        assertThat(notifications()).contains("Draw locked");

        navigate(AdminMatchesView.class, event);
        assertThat(test(find(Grid.class).single()).getCellText(0, 1)).isEqualTo("ada & bob vs cy & dee");
        click("Share draw");
        assertThat(find(TextArea.class).single().getValue()).contains("\n1. ada & bob vs cy & dee");
    }

    @Test
    void singlesDrawsHaveNoPartnerColumn() {
        String singles = events.createEvent("Singles", 2);
        navigate(AdminDrawView.class, singles);
        assertThat(grid().getColumns().get(PARTNER).isVisible()).isFalse();
    }
}
