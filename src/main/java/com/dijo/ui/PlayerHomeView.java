package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;
import static com.dijo.ui.PlayerUi.muted;
import static com.dijo.ui.PlayerUi.rows;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.events.DrawService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.dijo.events.PlayerDraw;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The player's dashboard: next matches and a summary of events and benefits. */
@Route("player")
@PageTitle("Player Home")
@RolesAllowed("USER")
public class PlayerHomeView extends MobileHomeLayout {
    private final PlayerBenefitsService benefits;
    private final DrawService draws;
    private final MatchService matches;
    private final EventUpdates updates;
    private final String me;
    private final VerticalLayout content = new VerticalLayout();
    private Runnable unsubscribe;

    public PlayerHomeView(PlayerBenefitsService benefits, DrawService draws, MatchService matches,
            EventUpdates updates, AuthenticationContext authentication) {
        super("Player Home");
        this.benefits = benefits;
        this.draws = draws;
        this.matches = matches;
        this.updates = updates;
        this.me = authentication.getPrincipalName().orElse("");
        content.setPadding(false);
        add(content);
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

    private void reload() {
        content.removeAll();
        Benefits mine;
        try {
            mine = benefits.myBenefits();
        } catch (IllegalArgumentException notSignedUp) {
            mine = null;
        }
        List<PlayerDraw> myDraws = draws.myDraws();
        List<Match> myMatches = matches.myPlayerMatches();
        String name = benefits.myName();
        content.add(PlayerUi.header(name, "Welcome, " + name, mine != null),
                nextMatches(myDraws, myMatches), atAGlance(mine, myDraws, myMatches));
    }

    /** Matches with both sides known and no confirmed result yet. */
    private Card nextMatches(List<PlayerDraw> myDraws, List<Match> myMatches) {
        List<Match> upcoming = myMatches.stream()
                .filter(m -> !m.confirmed() && !m.byeMatch() && m.ready())
                .toList();
        Card card = card("Next matches", upcoming.isEmpty() ? null
                : badge(String.valueOf(upcoming.size()), BadgeVariant.SUCCESS));
        if (upcoming.isEmpty()) {
            card.add(muted(myDraws.isEmpty() ? "You haven't been placed in an event yet."
                    : "No matches to play right now."));
            return card;
        }
        Div list = PlayerUi.list();
        for (Match m : upcoming) {
            String opponent = m.player1().equals(m.teamOf(me)) ? m.side2() : m.side1();
            list.add(listRow("vs " + opponent, new Span(m.eventName() + " · " + m.roundName()),
                    m.winner() == null ? badge("To be played")
                            : badge("Awaiting confirmation", BadgeVariant.WARNING)));
        }
        card.add(list);
        return card;
    }

    private Card atAGlance(Benefits mine, List<PlayerDraw> myDraws, List<Match> myMatches) {
        Map<String, List<Match>> byEvent = myMatches.stream()
                .collect(Collectors.groupingBy(Match::eventId));
        long stillIn = myDraws.stream()
                .filter(d -> !byEvent.containsKey(d.eventId())
                        || !PlayerMatchesView.eliminated(byEvent.get(d.eventId()),
                                byEvent.get(d.eventId()).getFirst().teamOf(me)))
                .count();
        String events = myDraws.isEmpty() ? "None yet"
                : myDraws.size() + " entered · " + stillIn + " still in";

        Card card = card("At a glance", null);
        if (mine == null) {
            card.add(rows("Events", events,
                    "Sign-up", benefits.settings()
                            .map(s -> "Pay " + PlayerEditorDialog.rupees(s.feePaise()) + " to sign up")
                            .orElse("Not open yet")));
            return card;
        }
        long available = mine.coupons().stream().filter(c -> c.redeemedAt() == null).count();
        card.add(rows("Events", events,
                "Welcome kit", mine.kitHandedOverAt() == null ? "Ready to collect" : "Collected",
                "Coupons", available + " of " + mine.coupons().size() + " available"));
        return card;
    }
}
