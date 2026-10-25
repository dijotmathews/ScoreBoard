package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;
import static com.dijo.ui.PlayerUi.muted;

import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.function.Function;

/**
 * The official's matches, grouped by what needs doing. Updates itself when the
 * admin assigns, confirms or reopens a match.
 */
@Route("official")
@PageTitle("Official Home")
@RolesAllowed("OFFICIAL")
public class OfficialHomeView extends MobileHomeLayout {
    private final MatchService matches;
    private final EventUpdates updates;
    private final String me;
    private final VerticalLayout content = new VerticalLayout();
    private Runnable unsubscribe;

    public OfficialHomeView(MatchService matches, EventUpdates updates,
            AuthenticationContext authentication) {
        super("Official Home");
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
        content.add(PlayerUi.header(me, "Welcome, " + me, badge("Official")));
        List<Match> mine = matches.myMatches();
        if (mine.isEmpty()) {
            Card card = card("Your matches", null);
            card.add(muted("No matches have been assigned to you yet."));
            content.add(card);
            return;
        }

        List<Match> toScore = filter(mine, Match.Status.SCHEDULED);
        List<Match> awaiting = filter(mine, Match.Status.AWAITING_CONFIRMATION);
        List<Match> upcoming = filter(mine, Match.Status.WAITING_FOR_PLAYERS);
        List<Match> done = filter(mine, Match.Status.CONFIRMED);

        if (toScore.isEmpty() && awaiting.isEmpty()) {
            content.add(muted("You're all caught up. Nothing to score right now."));
        }
        section("To score", toScore, BadgeVariant.WARNING, m -> resultButton(m, "Enter result", true));
        section("Awaiting confirmation", awaiting, null, m -> resultButton(m, "Update", false));
        section("Upcoming", upcoming, null, m -> badge("Waiting for players"));
        section("Completed", done, BadgeVariant.SUCCESS, m -> badge("Confirmed", BadgeVariant.SUCCESS));
    }

    private static List<Match> filter(List<Match> matches, Match.Status status) {
        return matches.stream().filter(m -> m.status() == status).toList();
    }

    /** A card listing matches in one state; hidden when there are none. */
    private void section(String title, List<Match> list, BadgeVariant variant,
            Function<Match, Component> end) {
        if (list.isEmpty()) return;
        Card card = variant == null ? card(title, badge(String.valueOf(list.size())))
                : card(title, badge(String.valueOf(list.size()), variant));
        Div rows = PlayerUi.list();
        for (Match m : list) {
            String detail = m.eventName() + " · " + m.roundName() + ", match " + m.matchNo()
                    + (m.winner() == null ? ""
                            : " · " + m.winnerName() + " won" + (m.score() == null ? "" : " " + m.score()));
            rows.add(listRow(m.side1() + " vs " + m.side2(), new Span(detail), end.apply(m)));
        }
        card.add(rows);
        content.add(card);
    }

    private Button resultButton(Match match, String label, boolean primary) {
        Button button = new Button(label,
                e -> new MatchResultDialog(matches, updates, match, false, this::reload).open());
        button.addThemeVariants(ButtonVariant.SMALL);
        if (primary) button.addThemeVariants(ButtonVariant.PRIMARY);
        return button;
    }
}
