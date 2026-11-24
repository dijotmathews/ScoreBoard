package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.dijo.events.EventService;
import com.dijo.events.EventSummary;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.dijo.events.MatchService.MatchTotals;
import com.dijo.events.OfficialService;
import com.dijo.events.OfficialService.Official;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Route("admin")
@PageTitle("Admin Home")
@RolesAllowed("ADMIN")
public class AdminHomeView extends VerticalLayout {
    private final MatchService matches;
    private final EventUpdates updates;
    private final Div remaining = new Div();
    private final TextField search = new TextField();
    private final Span showing = new Span();
    /** Every remaining match, before the search. */
    private List<Match> all = List.of();
    private Runnable unsubscribe;

    public AdminHomeView(PlayerBenefitsService benefits, EventService events, MatchService matches,
            OfficialService officials, EventUpdates updates) {
        this.matches = matches;
        this.updates = updates;
        RouterLink players = link(VaadinIcon.USERS, "Players and Benefits",
                "Player accounts, entry fee sign-ups, welcome kits and coupons.", AdminPlayersView.class);
        addBenefitsSummary(players, benefits);
        RouterLink eventsCard = link(VaadinIcon.TROPHY, "Manage events",
                "Create events, make the draws and run the matches.", AdminEventsView.class);
        addEventsSummary(eventsCard, events, matches);
        RouterLink officialsCard = link(VaadinIcon.CLIPBOARD_USER, "Officials",
                "Accounts for the people who score matches, and their workload.", AdminOfficialsView.class);
        addOfficialsSummary(officialsCard, officials);
        Div links = new Div(players, eventsCard, officialsCard);
        links.addClassName("home-links");
        H2 heading = new H2("Remaining matches");
        heading.addClassName("home-section-title");
        remaining.addClassName("home-remaining");
        search.setPlaceholder("Search by player or event");
        search.setAriaLabel("Search remaining matches by player or event");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(e -> showRemaining());
        showing.addClassName("muted");
        showing.addClassName("home-section-showing");
        Div top = new Div(heading, search);
        top.addClassName("home-section-header");
        Div section = new Div(top, showing, remaining);
        section.addClassName("home-section");
        add(PlayerUi.pageHeader("Admin Home", "Run the tournament from here."), links, section);
        reloadRemaining();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates, eventId -> true, this::reloadRemaining);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
    }

    /**
     * One card per event with its matches still to be played, as ordered by the service:
     * those with a court or time first, earliest first, then the rest in round order.
     */
    private void reloadRemaining() {
        all = matches.remainingMatches();
        showRemaining();
    }

    /** The remaining matches that match the search, keeping it across live reloads. */
    private void showRemaining() {
        remaining.removeAll();
        List<String> terms = List.of(search.getValue().strip().toLowerCase(Locale.ROOT).split("\\s+"));
        List<Match> list = all.stream().filter(m -> matchesSearch(m, terms)).toList();
        search.setVisible(!all.isEmpty());
        boolean filtered = list.size() < all.size();
        showing.setText(filtered ? "Showing " + list.size() + " of " + all.size() + " matches" : "");
        showing.setVisible(filtered);
        if (all.isEmpty()) {
            remaining.add(PlayerUi.muted("No matches to play. Lock an event's draw to create its matches."));
        } else if (list.isEmpty()) {
            remaining.add(PlayerUi.muted("No remaining matches for \"" + search.getValue().strip() + "\"."));
        }
        Map<String, List<Match>> byEvent = list.stream()
                .collect(Collectors.groupingBy(Match::eventId, LinkedHashMap::new, Collectors.toList()));
        byEvent.values().forEach(event -> remaining.add(eventCard(event)));
    }

    /**
     * Every word of the search must appear in the event name or a player's name (both
     * players of a doubles team) or username, so "ada singles" finds Ada's singles matches.
     */
    private static boolean matchesSearch(Match m, List<String> terms) {
        String text = Stream.of(m.eventName(), m.side1(), m.side2(),
                        m.player1(), m.player2(), m.player1Partner(), m.player2Partner())
                .filter(Objects::nonNull).collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(text::contains);
    }

    private Card eventCard(List<Match> list) {
        Match first = list.getFirst();
        long scheduled = list.stream().filter(Match::scheduled).count();
        Card card = card(first.eventName(), badge(list.size() + (list.size() == 1 ? " match" : " matches")));
        card.setSubtitle(scheduled + " with a court or time");
        Div rows = PlayerUi.list();
        for (Match m : list) {
            String detail = m.roundName() + " · Match " + m.matchNo()
                    + (m.official() == null ? "" : " · " + m.official());
            String where = PlayerUi.scheduleLabel(m);
            rows.add(listRow(m.side1() + " vs " + m.side2(), new Span(detail), actions(m,
                    where == null ? badge("Not scheduled") : badge(where, BadgeVariant.SUCCESS))));
        }
        card.add(rows);
        RouterLink open = new RouterLink("Open matches", AdminMatchesView.class, first.eventId());
        card.addToFooter(open);
        return card;
    }

    /** The row's schedule badge, then buttons to edit court, time and official, and the result. */
    private Div actions(Match match, Component badge) {
        Button schedule = new Button("Schedule", VaadinIcon.CALENDAR_CLOCK.create(),
                e -> new MatchScheduleDialog(matches, match, this::reloadRemaining).open());
        schedule.setAriaLabel("Court, time and official: " + match.side1() + " vs " + match.side2());
        Button result = new Button("Result", VaadinIcon.TROPHY.create(),
                e -> new MatchResultDialog(matches, updates, match, true, this::reloadRemaining).open());
        result.setAriaLabel("Result: " + match.side1() + " vs " + match.side2());
        result.setEnabled(match.ready());
        if (!match.ready()) result.setTooltipText("The players for this match are not known yet");
        for (Button b : List.of(schedule, result)) b.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        Div actions = new Div(badge, schedule, result);
        actions.addClassName("list-row-actions");
        return actions;
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
                meter("Draws ready", locked, all.size()),
                meter("Draw places filled", filled, slots),
                meter("Match results confirmed", totals.confirmed(), totals.total(), "no matches yet"));
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
