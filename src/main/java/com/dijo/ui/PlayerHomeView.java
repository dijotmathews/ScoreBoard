package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;
import static com.dijo.ui.PlayerUi.muted;
import static com.dijo.ui.PlayerUi.rows;

import com.dijo.benefits.PlayerAccountService;
import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.events.DrawService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.dijo.events.PlayerDraw;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The player's dashboard: what they still have to do (pay the entry fee, add a contact
 * number), next matches, and a summary of events and benefits.
 */
@Route("player")
@PageTitle("Player Home")
@RolesAllowed("USER")
public class PlayerHomeView extends MobileHomeLayout {
    private final PlayerBenefitsService benefits;
    private final PlayerAccountService accounts;
    private final DrawService draws;
    private final MatchService matches;
    private final EventUpdates updates;
    private final String me;
    private final VerticalLayout content = new VerticalLayout();
    private Runnable unsubscribe;

    public PlayerHomeView(PlayerBenefitsService benefits, PlayerAccountService accounts, DrawService draws,
            MatchService matches, EventUpdates updates, AuthenticationContext authentication) {
        super("Player Home");
        this.benefits = benefits;
        this.accounts = accounts;
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
        content.add(PlayerUi.header(name, "Welcome, " + name, mine != null));
        Card todo = toDo(mine != null, accounts.myPhone() != null);
        if (todo != null) content.add(todo);
        content.add(nextMatches(myDraws, myMatches), atAGlance(mine, myDraws, myMatches));
    }

    /** What the player still has to do, highlighted at the top; null when nothing is left. */
    private Card toDo(boolean paid, boolean hasPhone) {
        List<Component> items = new ArrayList<>();
        if (!paid) {
            items.add(toDoItem(VaadinIcon.WALLET, "Payment pending", benefits.settings()
                    .map(s -> "Pay the entry fee of " + PlayerEditorDialog.rupees(s.feePaise())
                            + " to the organisers. You are signed up once they record it.")
                    .orElse("The organisers will share the entry fee soon."), null));
        }
        if (!hasPhone) {
            Button add = new Button("Add contact number", VaadinIcon.PHONE.create(),
                    e -> getUI().ifPresent(ui -> ui.navigate(PlayerProfileView.class)));
            add.addThemeVariants(ButtonVariant.PRIMARY);
            items.add(toDoItem(VaadinIcon.PHONE, "Contact number missing",
                    "Add a mobile number so the organisers can reach you about your matches.", add));
        }
        if (items.isEmpty()) return null;
        Card card = card("To do", badge(String.valueOf(items.size()), BadgeVariant.WARNING));
        card.addClassName("todo-card");
        items.forEach(card::add);
        return card;
    }

    private static Div toDoItem(VaadinIcon icon, String title, String text, Button action) {
        Icon mark = icon.create();
        mark.addClassName("todo-icon");
        Span heading = new Span(title);
        heading.addClassName("todo-title");
        Div body = new Div(heading, muted(text));
        body.addClassName("todo-body");
        if (action != null) body.add(action);
        Div item = new Div(mark, body);
        item.addClassName("todo-item");
        return item;
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
