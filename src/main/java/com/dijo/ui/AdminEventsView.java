package com.dijo.ui;

import com.dijo.events.EventService;
import com.dijo.events.EventSummary;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

@Route("admin/events")
@PageTitle("Manage Events")
@RolesAllowed("ADMIN")
public class AdminEventsView extends VerticalLayout {
    private final EventService service;
    private final Grid<EventSummary> grid = new Grid<>(EventSummary.class, false);
    private final Span summary = new Span();

    public AdminEventsView(EventService service) {
        this.service = service;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        Button newEvent = new Button("New event", e -> openEditor(null));
        newEvent.addThemeVariants(ButtonVariant.PRIMARY);

        grid.addColumn(s -> s.event().name()).setHeader("Event").setFrozen(true).setSortable(true);
        grid.addColumn(s -> s.event().contestantCount()).setHeader("Contestants");
        grid.addColumn(s -> (s.event().drawLocked() ? "Locked • " : "")
                + s.filled() + " / " + s.event().contestantCount() + " filled")
                .setHeader("Draw");
        grid.getColumns().forEach(c -> c.setAutoWidth(true));
        grid.setSizeFull();
        grid.asSingleSelect().addValueChangeListener(e -> {
            if (e.getValue() != null) openEditor(e.getValue());
        });

        add(new H1("Manage Events"), newEvent, summary, grid);
        setFlexGrow(1, grid);
        reload();
    }

    private void openEditor(EventSummary event) {
        EventEditorDialog dialog = new EventEditorDialog(service, event, this::reload);
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened()) grid.deselectAll();
        });
        dialog.open();
    }

    private void reload() {
        List<EventSummary> events = service.eventSummaries();
        grid.setItems(events);
        summary.setText(events.isEmpty() ? "No events yet. Create one to get started."
                : events.size() + (events.size() == 1 ? " event" : " events")
                        + " • Select an event to edit it");
    }
}
