package com.dijo.ui;

import com.dijo.events.DrawService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.dijo.events.PlayerDraw;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The player's events: draw position, then progress through the knockout. Updates
 * itself (server push) whenever a draw or match result changes.
 */
@Route("player/matches")
@PageTitle("My Matches")
@RolesAllowed("USER")
public class PlayerMatchesView extends MobileHomeLayout {
    private final DrawService draws;
    private final MatchService matchService;
    private final EventUpdates updates;
    private final VerticalLayout events = new VerticalLayout();
    private Runnable unsubscribe;

    public PlayerMatchesView(DrawService draws, MatchService matchService, EventUpdates updates) {
        super("My Matches");
        this.draws = draws;
        this.matchService = matchService;
        this.updates = updates;
        events.setPadding(false);
        add(new H3("My Events"), events);
        reload();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // Any event can affect this player (e.g. being placed in a new draw).
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates, eventId -> true, this::reload);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
    }

    private void reload() {
        events.removeAll();
        List<PlayerDraw> mine = draws.myDraws();
        if (mine.isEmpty()) {
            events.add(new Span("You have not been placed in any event draw yet."));
        }
        Map<String, List<Match>> matchesByEvent = matchService.myPlayerMatches().stream()
                .collect(Collectors.groupingBy(Match::eventId));
        mine.forEach(draw -> events.add(eventCard(draw,
                matchesByEvent.getOrDefault(draw.eventId(), List.of()))));
    }

    private Card eventCard(PlayerDraw draw, List<Match> path) {
        Card card = new Card();
        card.setWidthFull();
        card.setTitle(draw.eventName());
        if (!draw.drawLocked() || path.isEmpty()) {
            card.setSubtitle("Slot " + draw.slot() + " • Draw not final yet");
            card.add(new Span(draw.opponentBye() ? "First match: BYE"
                    : draw.opponent() == null ? "First opponent not drawn yet"
                    : "First opponent: " + draw.opponent()));
            return card;
        }

        String me = myName(path.getFirst(), draw.slot());
        card.setSubtitle(progress(path, me));
        VerticalLayout rounds = new VerticalLayout();
        rounds.setPadding(false);
        rounds.setSpacing(false);
        path.forEach(match -> rounds.add(matchLine(match, me)));
        card.add(rounds);
        return card;
    }

    /** The signed-in player's username, taken from their first-round side. */
    private static String myName(Match first, int slot) {
        return slot % 2 == 1 ? first.player1() : first.player2();
    }

    /** "Champion", "Eliminated in …", or "Still in: …" based on confirmed results only. */
    private static String progress(List<Match> path, String me) {
        Match last = path.getLast();
        if (last.confirmed() && !me.equals(last.winner())) return "Eliminated in the " + last.roundName();
        if (last.confirmed() && last.round() == last.totalRounds()) return "Champion";
        if (last.confirmed()) return "Through to the next round";
        return "Still in: " + last.roundName();
    }

    private static Span matchLine(Match match, String me) {
        String opponent = me.equals(match.player1()) ? match.side2() : match.side1();
        String outcome;
        if (match.byeMatch()) {
            outcome = match.confirmed() ? "advanced (BYE)" : "BYE; awaiting confirmation";
        } else if (match.confirmed()) {
            boolean won = me.equals(match.winner());
            outcome = (won ? "won" : "lost")
                    + (match.score() == null ? "" : " (" + match.score() + ")")
                    + (won ? "" : " — eliminated");
        } else if (match.winner() != null) {
            outcome = "result awaiting confirmation";
        } else if (!match.ready()) {
            outcome = "waiting for the other match";
        } else {
            outcome = "to be played";
        }
        Span line = new Span(match.roundName() + ": vs " + opponent + " — " + outcome);
        if (match.confirmed() && !me.equals(match.winner())) {
            line.getStyle().set("font-weight", "600");
        }
        return line;
    }
}
