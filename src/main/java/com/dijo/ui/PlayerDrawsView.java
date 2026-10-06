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
    private Runnable unsubscribe;

    public PlayerDrawsView(EventService events, DrawService draws, MatchService matches,
            EventUpdates updates, AuthenticationContext authentication) {
        super("Event Draws");
        addClassName("wide");
        this.matches = matches;
        this.updates = updates;
        this.me = authentication.getPrincipalName().orElse("");


        List<Event> all = events.events();
        event.setLabel("Event");
        event.setItems(all);
        event.setItemLabelGenerator(Event::name);
        event.getStyle().set("max-width", "360px");
        // Open on an event the player is in, if any.
        List<String> mine = draws.myDraws().stream().map(PlayerDraw::eventId).toList();
        event.setValue(all.stream().filter(e -> mine.contains(e.id())).findFirst()
                .orElse(all.isEmpty() ? null : all.getFirst()));
        event.addValueChangeListener(e -> show());

        // The page fits the window; the bracket fills the rest and scrolls both ways.
        setHeightFull();
        bracket.addClassName("bracket-scroll");
        bracket.setWidthFull();
        bracket.getStyle().set("overflow", "auto").set("min-height", "0");
        add(event, bracket);
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
        Event selected = event.getValue();
        if (selected == null) {
            bracket.add(new Span("No events yet."));
            return;
        }
        List<Match> knockout = matches.bracket(selected.id());
        if (knockout.isEmpty()) {
            bracket.add(new Span("The draw for " + selected.name() + " is not final yet."));
            return;
        }
        bracket.add(new Html(BracketSvg.render(knockout, Objects.requireNonNull(me))));
    }
}
