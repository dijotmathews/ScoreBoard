package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;
import static com.dijo.ui.PlayerUi.muted;
import static com.dijo.ui.PlayerUi.rows;

import com.dijo.events.DrawService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.dijo.events.PlayerDraw;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
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
            events.add(muted("You have not been placed in any event draw yet."));
        }
        Map<String, List<Match>> matchesByEvent = matchService.myPlayerMatches().stream()
                .collect(Collectors.groupingBy(Match::eventId));
        mine.forEach(draw -> events.add(eventCard(draw,
                matchesByEvent.getOrDefault(draw.eventId(), List.of()))));
    }

    private Card eventCard(PlayerDraw draw, List<Match> path) {
        if (!draw.drawLocked() || path.isEmpty()) {
            Card card = card(draw.eventName(), badge("Draw not final", BadgeVariant.WARNING));
            card.add(rows("Slot", String.valueOf(draw.slot()),
                    "First opponent", draw.opponentBye() ? "BYE"
                            : draw.opponent() == null ? "Not drawn yet" : draw.opponentName()));
            return card;
        }

        String me = myName(path.getFirst(), draw.slot());
        Match last = path.getLast();
        Card card;
        if (eliminated(path, me)) {
            card = card(draw.eventName(), badge("Eliminated", BadgeVariant.ERROR));
            String round = last.roundName();
            card.setSubtitle("Lost in " + (round.startsWith("Round") ? round : "the " + round));
        } else if (last.confirmed() && last.round() == last.totalRounds()) {
            card = card(draw.eventName(), badge("Champion", BadgeVariant.SUCCESS, BadgeVariant.FILLED));
            card.setSubtitle("Won the final");
        } else {
            card = card(draw.eventName(), badge("Still in", BadgeVariant.SUCCESS));
            card.setSubtitle("Next: " + last.roundName());
        }
        Div list = PlayerUi.list();
        path.forEach(match -> list.add(matchRow(match, me)));
        card.add(list);
        return card;
    }

    /** The signed-in player's side, by its lead username (their own, or their team's lead in doubles). */
    private static String myName(Match first, int slot) {
        return slot % 2 == 1 ? first.player1() : first.player2();
    }

    /** True once a confirmed result has knocked this player out (path: their matches, in order). */
    static boolean eliminated(List<Match> path, String me) {
        Match last = path.getLast();
        return last.confirmed() && !me.equals(last.winner());
    }

    /** One round: opponent and score on the left, the outcome as a badge on the right. */
    private static Div matchRow(Match match, String me) {
        String opponent = me.equals(match.player1()) ? match.side2() : match.side1();
        String detail = match.roundName()
                + (match.score() == null || match.byeMatch() ? "" : " · " + match.score());
        Badge outcome;
        if (match.byeMatch()) {
            outcome = match.confirmed() ? badge("Advanced", BadgeVariant.SUCCESS)
                    : badge("Awaiting confirmation", BadgeVariant.WARNING);
        } else if (match.confirmed()) {
            outcome = me.equals(match.winner()) ? badge("Won", BadgeVariant.SUCCESS)
                    : badge("Lost", BadgeVariant.ERROR);
        } else if (match.winner() != null) {
            outcome = badge("Awaiting confirmation", BadgeVariant.WARNING);
        } else if (!match.ready()) {
            outcome = badge("Waiting for opponent");
        } else {
            outcome = badge("To be played");
        }
        Div row = listRow("vs " + opponent, new Span(detail), outcome);
        if (match.confirmed() && !me.equals(match.winner())) row.addClassName("dim");
        return row;
    }
}
