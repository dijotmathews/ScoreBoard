package com.dijo.ui;

import com.dijo.events.Event;
import com.dijo.events.EventService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

/**
 * Admin view of an event's knockout: assign officials, enter and confirm results.
 * Refreshes itself (server push) when results for this event change elsewhere.
 */
@Route("admin/matches")
@RolesAllowed("ADMIN")
public class AdminMatchesView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {
    private final EventService events;
    private final MatchService matches;
    private final EventUpdates updates;
    private Runnable unsubscribe;
    private final H1 heading = new H1("Matches");
    private final Span summary = new Span();
    private final HorizontalLayout links = new HorizontalLayout();
    private final LogoutButton logout;
    private final Grid<Match> grid = new Grid<>(Match.class, false);
    private Event event;
    private List<String> officials = List.of();

    public AdminMatchesView(EventService events, MatchService matches, EventUpdates updates,
            AuthenticationContext authentication) {
        this.updates = updates;
        this.logout = new LogoutButton(authentication);
        links.setAlignItems(Alignment.BASELINE);
        this.events = events;
        this.matches = matches;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        grid.addColumn(Match::roundName).setHeader("Round").setAutoWidth(true);
        grid.addColumn(Match::matchNo).setHeader("Match").setAutoWidth(true);
        grid.addColumn(Match::side1).setHeader("Player 1").setAutoWidth(true);
        grid.addColumn(Match::side2).setHeader("Player 2").setAutoWidth(true);
        grid.addComponentColumn(this::officialPicker).setHeader("Official").setAutoWidth(true);
        grid.addColumn(m -> m.winner() == null ? "—"
                : m.winner() + (m.score() == null ? "" : " (" + m.score() + ")"))
                .setHeader("Winner").setAutoWidth(true);
        grid.addColumn(m -> statusText(m.status())).setHeader("Status").setAutoWidth(true);
        grid.addComponentColumn(m -> {
            Button result = new Button(m.status() == Match.Status.AWAITING_CONFIRMATION
                    ? "Review" : "Result", e -> new MatchResultDialog(matches, m, true, this::reload).open());
            result.addThemeVariants(ButtonVariant.SMALL);
            if (m.status() == Match.Status.AWAITING_CONFIRMATION) result.addThemeVariants(ButtonVariant.PRIMARY);
            result.setEnabled(m.ready());
            return result;
        }).setAutoWidth(true);
        grid.setSizeFull();

        add(heading, links, summary, grid);
        setFlexGrow(1, grid);
    }

    @Override
    public void setParameter(BeforeEvent beforeEvent, String eventId) {
        event = events.event(eventId).orElse(null);
        links.removeAll();
        links.add(new RouterLink("Manage events", AdminEventsView.class));
        if (event != null) links.add(new RouterLink("Draw", AdminDrawView.class, event.id()));
        links.add(logout);
        reload();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates,
                eventId -> event != null && eventId.equals(event.id()), this::reload);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
    }

    @Override
    public String getPageTitle() {
        return event == null ? "Matches" : "Matches: " + event.name();
    }

    static String statusText(Match.Status status) {
        return switch (status) {
            case WAITING_FOR_PLAYERS -> "Waiting for players";
            case SCHEDULED -> "Scheduled";
            case AWAITING_CONFIRMATION -> "Awaiting confirmation";
            case CONFIRMED -> "Confirmed";
        };
    }

    private ComboBox<String> officialPicker(Match match) {
        ComboBox<String> picker = new ComboBox<>();
        picker.setItems(officials);
        picker.setValue(match.official());
        picker.setPlaceholder("Assign official");
        picker.setClearButtonVisible(true);
        picker.setReadOnly(match.confirmed());
        picker.setAriaLabel("Official for " + match.roundName() + " match " + match.matchNo());
        picker.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            try {
                matches.assignOfficial(event.id(), match.round(), match.matchNo(), e.getValue());
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.ERROR);
            }
            reload();
        });
        return picker;
    }

    private void reload() {
        if (event == null) {
            heading.setText("Matches");
            summary.setText("Event not found.");
            grid.setItems(List.of());
            return;
        }
        event = events.event(event.id()).orElse(event);
        heading.setText("Matches: " + event.name());
        List<Match> all = matches.matches(event.id());
        officials = matches.officials();
        grid.setItems(all);
        if (all.isEmpty()) {
            summary.setText("Lock the draw to create the matches.");
            return;
        }
        long confirmed = all.stream().filter(Match::confirmed).count();
        long awaiting = all.stream().filter(m -> m.status() == Match.Status.AWAITING_CONFIRMATION).count();
        Match fin = all.getLast();
        summary.setText(fin.confirmed() ? "Champion: " + fin.winner()
                : confirmed + " of " + all.size() + " matches confirmed"
                        + (awaiting > 0 ? " • " + awaiting + " awaiting your confirmation" : "")
                        + (officials.isEmpty() ? " • No officials available to assign" : ""));
    }
}
