package com.dijo.ui;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.dijo.events.EventService;
import com.dijo.events.EventSummary;
import com.dijo.events.MatchService;
import com.dijo.events.MatchService.MatchTotals;
import com.dijo.events.OfficialService;
import com.dijo.events.OfficialService.Official;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

@Route("admin")
@PageTitle("Admin Home")
@RolesAllowed("ADMIN")
public class AdminHomeView extends VerticalLayout {

    public AdminHomeView(PlayerBenefitsService benefits, EventService events, MatchService matches,
            OfficialService officials) {
        RouterLink players = link(VaadinIcon.USERS, "Players and Benefits",
                "Player accounts, entry fee sign-ups, welcome kits and coupons.", AdminPlayersView.class);
        addBenefitsSummary(players, benefits);
        RouterLink eventsCard = link(VaadinIcon.TROPHY, "Manage events",
                "Events, their draws and knockout matches.", AdminEventsView.class);
        addEventsSummary(eventsCard, events, matches);
        RouterLink officialsCard = link(VaadinIcon.CLIPBOARD_USER, "Officials",
                "Accounts for the people who score matches, and their workload.", AdminOfficialsView.class);
        addOfficialsSummary(officialsCard, officials);
        Div links = new Div(players, eventsCard, officialsCard);
        links.addClassName("home-links");
        add(PlayerUi.pageHeader("Admin Home", "Run the tournament from here."), links);
    }

    /** The Players and Benefits page figures as mini meters, with the entry fee as a badge. */
    private static void addBenefitsSummary(RouterLink card, PlayerBenefitsService benefits) {
        List<PlayerBenefitRow> rows = benefits.listPlayerBenefits();
        long signedUp = rows.stream().filter(PlayerBenefitRow::paid).count();
        long collected = rows.stream().filter(r -> r.kitHandedOverAt() != null).count();
        long couponsLeft = rows.stream().mapToLong(PlayerBenefitRow::couponsRemaining).sum();
        long couponsIssued = rows.stream().mapToLong(PlayerBenefitRow::couponsIssued).sum();

        summarise(card, benefits.settings()
                        .map(s -> "Entry fee " + PlayerEditorDialog.rupees(s.feePaise())).orElse("Entry fee not set"),
                meter("Signed up", signedUp, rows.size()),
                meter("Kits collected", collected, signedUp),
                meter("Coupons left", couponsLeft, couponsIssued));
    }

    /** Manage events figures: draws locked, slots filled and matches confirmed. */
    private static void addEventsSummary(RouterLink card, EventService events, MatchService matches) {
        List<EventSummary> all = events.eventSummaries();
        long locked = all.stream().filter(s -> s.event().drawLocked()).count();
        long slots = all.stream().mapToLong(s -> s.event().contestantCount()).sum();
        long filled = all.stream().mapToLong(EventSummary::filled).sum();
        MatchTotals totals = matches.totals();
        summarise(card, all.size() + (all.size() == 1 ? " event" : " events"),
                meter("Draws locked", locked, all.size()),
                meter("Slots filled", filled, slots),
                meter("Matches confirmed", totals.confirmed(), totals.total(), "no matches yet"));
    }

    /** Officials figures: active accounts, open matches covered, assigned matches completed. */
    private static void addOfficialsSummary(RouterLink card, OfficialService officials) {
        List<Official> all = officials.officials();
        long active = all.stream().filter(Official::enabled).count();
        long covered = all.stream().mapToLong(Official::open).sum();
        long needing = covered + officials.unassignedMatches();
        long assigned = all.stream().mapToLong(Official::assigned).sum();
        long completed = all.stream().mapToLong(Official::completed).sum();
        summarise(card, all.size() + (all.size() == 1 ? " official" : " officials"),
                meter("Active", active, all.size()),
                meter("Open matches covered", covered, needing, "none open"),
                meter("Assigned matches completed", completed, assigned));
    }

    /** Adds the corner pill and the meters below the card's description. */
    private static void summarise(RouterLink card, String chipText, Div... meters) {
        Span chip = new Span(chipText);
        chip.addClassName("home-link-chip");
        card.addComponentAtIndex(1, chip);
        Div list = new Div(meters);
        list.addClassName("home-meters");
        card.add(list);
    }

    private static Div meter(String label, long value, long total) {
        return meter(label, value, total, "none yet");
    }

    /** emptyText replaces "0 of 0" when there is nothing to count yet. */
    private static Div meter(String label, long value, long total, String emptyText) {
        Span name = new Span(label);
        name.addClassName("muted");
        Span figure = new Span();
        if (total == 0) {
            figure.add(PlayerUi.muted(emptyText));
        } else {
            Span strong = new Span(String.valueOf(value));
            strong.addClassName("home-meter-value");
            figure.add(strong, new Span(" of " + total));
        }
        Div top = new Div(name, figure);
        top.addClassName("home-meter-top");
        ProgressBar bar = new ProgressBar(0, Math.max(total, 1), Math.min(value, Math.max(total, 1)));
        bar.getElement().setAttribute("aria-label", label + ": " + value + " of " + total);
        Div meter = new Div(top, bar);
        meter.addClassName("home-meter");
        return meter;
    }

    private static RouterLink link(VaadinIcon icon, String title, String description,
            Class<? extends Component> view) {
        Div badge = new Div(icon.create());
        badge.addClassName("home-link-icon");
        Icon arrow = VaadinIcon.ARROW_RIGHT.create();
        arrow.addClassName("home-link-arrow");
        Span heading = new Span(title);
        heading.addClassName("home-link-title");
        RouterLink link = new RouterLink(view);
        link.add(badge, arrow, heading, PlayerUi.muted(description));
        link.addClassName("home-link");
        return link;
    }
}
