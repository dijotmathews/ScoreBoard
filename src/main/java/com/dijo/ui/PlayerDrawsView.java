package com.dijo.ui;

import com.dijo.events.DrawService;
import com.dijo.events.Event;
import com.dijo.events.EventService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.dijo.events.PlayerDraw;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.Objects;

/** The full knockout draw of any event, with the signed-in player's name in bold. */
@Route("player/draws")
@PageTitle("Event Draws")
@RolesAllowed("USER")
public class PlayerDrawsView extends MobileHomeLayout {
    private final MatchService matches;
    private final EventUpdates updates;
    private final String me;
    private final Select<Event> event = new Select<>();
    private final Div bracket = new Div();
    private final Div status = new Div();
    private final Span summary = new Span();
    private final Span note = new Span("Your matches are highlighted.");
    private Runnable unsubscribe;

    public PlayerDrawsView(EventService events, DrawService draws, MatchService matches,
            EventUpdates updates, AuthenticationContext authentication) {
        super("Event Draws");
        addClassName("wide");
        this.matches = matches;
        this.updates = updates;
        this.me = authentication.getPrincipalName().orElse("");

        List<Event> all = events.events();
        event.setAriaLabel("Event");
        event.setItems(all);
        event.setItemLabelGenerator(Event::name);
        // Open on an event the player is in, if any.
        List<String> mine = draws.myDraws().stream().map(PlayerDraw::eventId).toList();
        event.setValue(all.stream().filter(e -> mine.contains(e.id())).findFirst()
                .orElse(all.isEmpty() ? null : all.getFirst()));
        event.addValueChangeListener(e -> show());

        // Picker and status on one line, a summary below, then the bracket panel.
        status.addClassName("draw-toolbar");
        status.add(event);
        summary.addClassName("muted");
        note.addClassName("muted");
        note.getStyle().set("font-size", "0.9em");

        // The page fits the window; the bracket panel fills the rest and scrolls both ways.
        setHeightFull();
        bracket.addClassNames("bracket-scroll", "bracket-panel");
        bracket.setWidthFull();
        add(status, summary, bracket, note);
        setFlexGrow(1, bracket);
        show();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates,
                eventId -> event.getValue() != null && eventId.equals(event.getValue().id()), this::show);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
    }

    private void show() {
        bracket.removeAll();
        status.getChildren().filter(c -> c != event).toList().forEach(status::remove);
        summary.setText("");
        note.setVisible(false);
        Event selected = event.getValue();
        if (selected == null) {
            bracket.add(emptyState("No events yet."));
            return;
        }
        List<Match> knockout = matches.bracket(selected.id());
        if (knockout.isEmpty()) {
            status.add(PlayerUi.badge("Draw not final", BadgeVariant.WARNING));
            bracket.add(emptyState("The draw for " + selected.name()
                    + " isn't final yet. It will appear here once the organisers lock it."));
            return;
        }

        Match fin = knockout.getLast();
        long played = knockout.stream().filter(Match::confirmed).count();
        long players = knockout.stream().filter(m -> m.round() == 1)
                .mapToLong(m -> (m.player1() != null ? 1 : 0) + (m.player2() != null ? 1 : 0)).sum();
        if (fin.confirmed()) {
            status.add(PlayerUi.badge("Complete", BadgeVariant.SUCCESS));
            summary.setText("Champion: " + fin.winnerName() + " · " + players + " players");
        } else {
            status.add(PlayerUi.badge("In progress"));
            summary.setText(players + " players · " + played + " of " + knockout.size() + " matches played");
        }
        // In doubles the bracket shows the player's team by its lead.
        String mySide = knockout.stream().filter(m -> m.round() == 1).map(m -> m.teamOf(me))
                .filter(Objects::nonNull).findFirst().orElse(null);
        note.setVisible(mySide != null);
        bracket.add(new Html(BracketSvg.render(knockout, mySide)));
    }

    private static Div emptyState(String text) {
        Div empty = new Div(PlayerUi.muted(text));
        empty.addClassName("bracket-empty");
        return empty;
    }
}
