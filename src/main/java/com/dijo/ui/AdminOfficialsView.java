package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;

import com.dijo.events.EventUpdates;
import com.dijo.events.OfficialService;
import com.dijo.events.OfficialService.Official;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.Locale;

/** Admin management of official accounts and their match workload. */
@Route("admin/officials")
@PageTitle("Officials")
@RolesAllowed("ADMIN")
public class AdminOfficialsView extends VerticalLayout {
    private final OfficialService service;
    private final EventUpdates updates;
    private final Div stats = new Div();
    private final TextField search = new TextField();
    private final Span showing = new Span();
    private final Grid<Official> grid = new Grid<>(Official.class, false);
    private List<Official> officials = List.of();
    private Runnable unsubscribe;

    public AdminOfficialsView(OfficialService service, EventUpdates updates) {
        this.service = service;
        this.updates = updates;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        Button newOfficial = new Button("New official", VaadinIcon.PLUS.create(), e -> openEditor(null));
        newOfficial.addThemeVariants(ButtonVariant.PRIMARY);
        Div header = PlayerUi.pageHeader("Officials",
                "Accounts for the people who score matches. Select an official to edit them.", newOfficial);

        stats.addClassName("stat-tiles");

        search.setPlaceholder("Search officials");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setAriaLabel("Search officials");
        search.addValueChangeListener(e -> applyFilter());
        showing.addClassName("muted");
        Div toolbar = new Div(search, showing);
        toolbar.addClassName("grid-toolbar");

        grid.addComponentColumn(this::nameCell).setHeader("Official").setFrozen(true)
                .setComparator(Official::username).setSortable(true).setFlexGrow(2);
        grid.addComponentColumn(o -> o.enabled() ? badge("Active", BadgeVariant.SUCCESS)
                : badge("Disabled", BadgeVariant.ERROR)).setHeader("Account");
        grid.addColumn(Official::assigned).setHeader("Assigned").setTextAlign(ColumnTextAlign.END)
                .setSortable(true);
        grid.addColumn(Official::open).setHeader("To score").setTextAlign(ColumnTextAlign.END)
                .setSortable(true);
        grid.addColumn(Official::completed).setHeader("Completed").setTextAlign(ColumnTextAlign.END)
                .setSortable(true);
        grid.getColumns().forEach(c -> c.setAutoWidth(true));
        grid.addThemeVariants(GridVariant.ROW_STRIPES);
        grid.setSizeFull();
        grid.asSingleSelect().addValueChangeListener(e -> {
            if (e.getValue() != null) openEditor(e.getValue());
        });

        add(header, stats, toolbar, grid);
        setFlexGrow(1, grid);
        reload();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // Workloads change as matches are assigned and confirmed.
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates, eventId -> true, this::reload);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
    }

    private Div nameCell(Official official) {
        Avatar avatar = new Avatar(official.username());
        avatar.addThemeName("xsmall");
        Div cell = new Div(avatar, new Span(official.username()));
        cell.addClassName("player-cell");
        return cell;
    }

    private void openEditor(Official official) {
        OfficialEditorDialog dialog = new OfficialEditorDialog(service, official, this::reload);
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened()) grid.deselectAll();
        });
        dialog.open();
    }

    private void reload() {
        officials = service.officials();
        long active = officials.stream().filter(Official::enabled).count();
        int assigned = officials.stream().mapToInt(Official::assigned).sum();
        int open = officials.stream().mapToInt(Official::open).sum();
        int unassigned = service.unassignedMatches();
        stats.removeAll();
        stats.add(PlayerUi.statTile("Officials", String.valueOf(officials.size()), active + " active"),
                PlayerUi.statTile("Matches assigned", String.valueOf(assigned), "across all events"),
                PlayerUi.statTile("To score", String.valueOf(open), "assigned, not yet confirmed"),
                PlayerUi.statTile("Unassigned", String.valueOf(unassigned),
                        unassigned > 0 ? "open matches need an official" : "every open match is covered"));
        applyFilter();
    }

    private void applyFilter() {
        String term = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<Official> visible = officials.stream()
                .filter(o -> o.username().toLowerCase(Locale.ROOT).contains(term)).toList();
        grid.setItems(visible);
        showing.setText(officials.isEmpty() ? "No officials yet. Create one to get started."
                : visible.size() == officials.size()
                        ? officials.size() + (officials.size() == 1 ? " official" : " officials")
                        : "Showing " + visible.size() + " of " + officials.size() + " officials");
    }
}
