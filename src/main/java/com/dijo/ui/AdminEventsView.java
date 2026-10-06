package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;

import com.dijo.events.EventService;
import com.dijo.events.EventSummary;
import com.dijo.events.EventUpdates;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.Locale;

@Route("admin/events")
@PageTitle("Manage Events")
@RolesAllowed("ADMIN")
public class AdminEventsView extends VerticalLayout {
    private final EventService service;
    private final EventUpdates updates;
    private final Div stats = new Div();
    private final TextField search = new TextField();
    private final Span showing = new Span();
    private final Grid<EventSummary> grid = new Grid<>(EventSummary.class, false);
    private List<EventSummary> events = List.of();
    private Runnable unsubscribe;

    public AdminEventsView(EventService service, EventUpdates updates) {
        this.service = service;
        this.updates = updates;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        Button newEvent = new Button("New event", VaadinIcon.PLUS.create(), e -> openEditor(null));
        newEvent.addThemeVariants(ButtonVariant.PRIMARY);
        Div header = PlayerUi.pageHeader("Manage Events",
                "Events, their draws and knockout matches. Select an event to edit it.", newEvent);

        stats.addClassName("stat-tiles");

        search.setPlaceholder("Search events");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setAriaLabel("Search events");
        search.addValueChangeListener(e -> applyFilter());
        showing.addClassName("muted");
        Div toolbar = new Div(search, showing);
        toolbar.addClassName("grid-toolbar");

        grid.addColumn(s -> s.event().name()).setHeader("Event").setFrozen(true)
                .setSortable(true).setFlexGrow(2);
        grid.addColumn(s -> s.event().contestantCount()).setHeader("Contestants").setSortable(true);
        grid.addComponentColumn(this::status).setHeader("Draw");
        grid.addComponentColumn(this::filled).setHeader("Filled").setWidth("200px").setFlexGrow(0);
        grid.addComponentColumn(this::actions).setHeader("").setFlexGrow(0).setAutoWidth(true);
        grid.getColumns().forEach(c -> { if (c.getWidth() == null) c.setAutoWidth(true); });
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
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates, eventId -> true, this::reload);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
    }

    private Badge status(EventSummary s) {
        if (s.event().drawLocked()) return badge("Locked", BadgeVariant.SUCCESS);
        if (s.filled() == 0) return badge("Empty");
        if (s.filled() == s.event().contestantCount()) return badge("Ready to lock", BadgeVariant.WARNING);
        return badge("Filling");
    }

    private Div filled(EventSummary s) {
        ProgressBar bar = new ProgressBar(0, s.event().contestantCount(), s.filled());
        bar.setWidth("90px");
        Div cell = new Div(bar, new Span(s.filled() + " of " + s.event().contestantCount()));
        cell.addClassName("filled-cell");
        return cell;
    }

    private Div actions(EventSummary s) {
        Button draw = new Button("Draw", e ->
                getUI().ifPresent(ui -> ui.navigate(AdminDrawView.class, s.event().id())));
        Button matches = new Button("Matches", e ->
                getUI().ifPresent(ui -> ui.navigate(AdminMatchesView.class, s.event().id())));
        matches.setEnabled(s.event().drawLocked());
        if (!s.event().drawLocked()) matches.setTooltipText("Lock the draw to create matches");
        for (Button b : List.of(draw, matches)) b.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        Div cell = new Div(draw, matches);
        cell.addClassName("row-actions");
        return cell;
    }

    private void openEditor(EventSummary event) {
        EventEditorDialog dialog = new EventEditorDialog(service, event, this::reload);
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened()) grid.deselectAll();
        });
        dialog.open();
    }

    private void reload() {
        events = service.eventSummaries();
        long locked = events.stream().filter(s -> s.event().drawLocked()).count();
        long ready = events.stream().filter(s -> !s.event().drawLocked()
                && s.filled() == s.event().contestantCount()).count();
        int slots = events.stream().mapToInt(s -> s.event().contestantCount()).sum();
        int filled = events.stream().mapToInt(EventSummary::filled).sum();
        stats.removeAll();
        stats.add(PlayerUi.statTile("Events", String.valueOf(events.size()), null),
                PlayerUi.statTile("Draws locked", String.valueOf(locked), "of " + events.size() + " events"),
                PlayerUi.statTile("Slots filled", String.valueOf(filled), "of " + slots + " slots"),
                PlayerUi.statTile("Ready to lock", String.valueOf(ready), "full draws not locked"));
        applyFilter();
    }

    private void applyFilter() {
        String term = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<EventSummary> visible = events.stream()
                .filter(s -> s.event().name().toLowerCase(Locale.ROOT).contains(term)).toList();
        grid.setItems(visible);
        showing.setText(events.isEmpty() ? "No events yet. Create one to get started."
                : visible.size() == events.size() ? events.size() + (events.size() == 1 ? " event" : " events")
                : "Showing " + visible.size() + " of " + events.size() + " events");
    }
}
