package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;

import com.dijo.events.Event;
import com.dijo.events.EventService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * Admin view of an event's knockout: assign officials, enter and confirm results.
 * Refreshes itself (server push) when results for this event change elsewhere.
 * Court and time are saved as they are entered; they appear in the shared draw
 * (message and image) and on Admin Home.
 */
@Route("admin/matches")
@RolesAllowed("ADMIN")
public class AdminMatchesView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {
    /** Quick filter over the match list; null status means all. */
    private enum Show {
        ALL("All matches", null),
        AWAITING("Awaiting confirmation", Match.Status.AWAITING_CONFIRMATION),
        TO_PLAY("To play", Match.Status.SCHEDULED),
        WAITING("Waiting for players", Match.Status.WAITING_FOR_PLAYERS),
        CONFIRMED("Confirmed", Match.Status.CONFIRMED);

        final String label;
        final Match.Status status;

        Show(String label, Match.Status status) {
            this.label = label;
            this.status = status;
        }
    }

    private final EventService events;
    private final MatchService matches;
    private final EventUpdates updates;
    private final Div header = new Div();
    private final Div stats = new Div();
    private final Select<Show> show = new Select<>();
    private final Span showing = new Span();
    private final Grid<Match> grid = new Grid<>(Match.class, false);
    private final Div bracket = new Div();
    private Event event;
    /** Every match of the knockout, for drawing the bracket. */
    private List<Match> knockout = List.of();
    /** The matches to play: the knockout without those between two BYEs. */
    private List<Match> all = List.of();
    private List<String> officials = List.of();
    private Runnable unsubscribe;

    public AdminMatchesView(EventService events, MatchService matches, EventUpdates updates) {
        this.events = events;
        this.matches = matches;
        this.updates = updates;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        RouterLink back = new RouterLink("← All events", AdminEventsView.class);
        back.addClassName("back-link");
        header.setWidthFull();
        stats.addClassName("stat-tiles");

        show.setItems(Show.values());
        show.setItemLabelGenerator(s -> s.label);
        show.setValue(Show.ALL);
        show.setAriaLabel("Show");
        show.addValueChangeListener(e -> applyFilter());
        showing.addClassName("muted");
        Div toolbar = new Div(show, showing);
        toolbar.addClassName("grid-toolbar");

        grid.addColumn(m -> m.roundName() + " · " + m.matchNo()).setHeader("Match").setAutoWidth(true);
        grid.addColumn(m -> m.side1() + " vs " + m.side2()).setHeader("Players").setFlexGrow(2);
        grid.addComponentColumn(this::officialCell).setHeader("Official").setWidth("200px").setFlexGrow(0);
        grid.addComponentColumn(this::courtCell).setHeader("Court").setWidth("110px").setFlexGrow(0);
        grid.addComponentColumn(this::timeCell).setHeader("Time").setWidth("140px").setFlexGrow(0);
        grid.addColumn(m -> m.winner() == null ? "—"
                : m.winnerName() + (m.score() == null ? "" : " · " + m.score()))
                .setHeader("Result").setFlexGrow(2);
        grid.addComponentColumn(m -> statusBadge(m.status())).setHeader("Status").setAutoWidth(true);
        grid.addComponentColumn(this::resultButton).setAutoWidth(true).setFlexGrow(0);
        // Shade every other round so each round reads as a group.
        grid.setPartNameGenerator(m -> m.round() % 2 == 0 ? "match-alt" : null);
        grid.addThemeVariants(GridVariant.NO_ROW_BORDERS);
        grid.setSizeFull();

        VerticalLayout list = new VerticalLayout(toolbar, grid);
        list.setPadding(false);
        list.setSizeFull();
        list.setFlexGrow(1, grid);

        // Fill the tab and scroll both ways, so large draws are reachable on short screens.
        bracket.addClassNames("bracket-scroll", "bracket-panel");
        bracket.setSizeFull();
        TabSheet tabs = new TabSheet();
        tabs.add("Matches", list);
        tabs.add("Draw", bracket);
        tabs.setSizeFull();
        add(back, header, stats, tabs);
        setFlexGrow(1, tabs);
    }

    @Override
    public void setParameter(BeforeEvent beforeEvent, String eventId) {
        event = events.event(eventId).orElse(null);
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

    private static Badge statusBadge(Match.Status status) {
        return switch (status) {
            case WAITING_FOR_PLAYERS -> badge("Waiting");
            case SCHEDULED -> badge("To play");
            case AWAITING_CONFIRMATION -> badge("Awaiting confirmation", BadgeVariant.WARNING);
            case CONFIRMED -> badge("Confirmed", BadgeVariant.SUCCESS);
            case NO_MATCH -> badge("No match");
        };
    }

    private Button resultButton(Match m) {
        boolean review = m.status() == Match.Status.AWAITING_CONFIRMATION;
        Button result = new Button(review ? "Review" : "Result",
                e -> new MatchResultDialog(matches, updates, m, true, this::reload).open());
        result.addThemeVariants(ButtonVariant.SMALL);
        if (review) result.addThemeVariants(ButtonVariant.PRIMARY);
        else result.addThemeVariants(ButtonVariant.TERTIARY);
        result.setEnabled(m.ready());
        return result;
    }

    /** A picker while the match can still be officiated; plain text once settled. */
    private Component officialCell(Match match) {
        if (match.confirmed() || match.byeMatch()) {
            return match.official() == null ? PlayerUi.muted("—") : new Span(match.official());
        }
        return officialPicker(match);
    }

    private ComboBox<String> officialPicker(Match match) {
        ComboBox<String> picker = new ComboBox<>();
        picker.setItems(officials);
        picker.setValue(match.official());
        picker.setPlaceholder("Assign official");
        picker.setClearButtonVisible(true);
        picker.setWidthFull();
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

    private Component courtCell(Match match) {
        if (!match.remaining()) return PlayerUi.muted("—");
        TextField court = new TextField();
        court.setValue(match.court() == null ? "" : match.court());
        court.setPlaceholder("Court");
        court.setWidthFull();
        court.setAriaLabel("Court for " + match.roundName() + " match " + match.matchNo());
        court.addValueChangeListener(e -> {
            if (e.isFromClient()) saveSchedule(match, e.getValue(), current(match).startTime());
        });
        return court;
    }

    private Component timeCell(Match match) {
        if (!match.remaining()) return PlayerUi.muted("—");
        TimePicker time = new TimePicker();
        time.setStep(Duration.ofMinutes(15));
        time.setValue(match.startTime());
        time.setPlaceholder("Time");
        time.setWidthFull();
        time.setAriaLabel("Time for " + match.roundName() + " match " + match.matchNo());
        time.addValueChangeListener(e -> {
            if (e.isFromClient()) saveSchedule(match, current(match).court(), e.getValue());
        });
        return time;
    }

    /** The match as now stored, so saving the court keeps a time saved since, and vice versa. */
    private Match current(Match match) {
        return matches.currentMatch(match.eventId(), match.round(), match.matchNo()).orElse(match);
    }

    /** Saves without reloading the grid, so focus stays where the admin is typing. */
    private void saveSchedule(Match match, String court, LocalTime time) {
        try {
            matches.schedule(match.eventId(), match.round(), match.matchNo(), court, time);
            Match saved = current(match);
            all = all.stream().map(m -> m.round() == saved.round() && m.matchNo() == saved.matchNo()
                    ? saved : m).toList();
            knockout = knockout.stream().map(m -> m.round() == saved.round() && m.matchNo() == saved.matchNo()
                    ? saved : m).toList();
        } catch (IllegalArgumentException ex) {
            Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.ERROR);
            reload();
        }
    }

    /**
     * Opens a ready-to-send message with the matches still to be played (no confirmed
     * result, BYEs left out), round by round, with any court and time entered, plus a
     * picture of the whole draw with the results so far and those courts and times. Once
     * every match is played, only the picture is offered.
     */
    private void shareDraw() {
        String text = null;
        List<Match> remaining = all.stream().filter(Match::remaining).toList();
        if (!remaining.isEmpty()) {
            StringBuilder b = new StringBuilder("*" + event.name() + " – Remaining matches*\n");
            int round = 0;
            for (Match m : remaining) {
                if (m.round() != round) {
                    round = m.round();
                    b.append("\n").append(m.roundName()).append("\n");
                }
                b.append(m.matchNo()).append(". ").append(m.side1()).append(" vs ").append(m.side2());
                String where = PlayerUi.scheduleLabel(m);
                if (where != null) b.append(" – ").append(where);
                b.append("\n");
            }
            text = b.toString().stripTrailing();
        }
        String svg = BracketSvg.image(knockout, event.name(), m -> m.remaining() ? PlayerUi.scheduleLabel(m) : null);
        new ShareMessageDialog("Share draw", text, svg, drawFileName()).open();
    }

    private String drawFileName() {
        return event.name().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "") + "-draw.png";
    }

    private void reload() {
        header.removeAll();
        stats.removeAll();
        bracket.removeAll();
        if (event == null) {
            header.add(PlayerUi.pageHeader("Matches", "This event was not found."));
            knockout = all = List.of();
            applyFilter();
            return;
        }
        event = events.event(event.id()).orElse(event);
        Button editDraw = new Button("Edit draw", VaadinIcon.EDIT.create(),
                e -> getUI().ifPresent(ui -> ui.navigate(AdminDrawView.class, event.id())));
        knockout = matches.matches(event.id());
        all = knockout.stream().filter(m -> !m.noMatch()).toList();
        officials = matches.officials();

        if (all.isEmpty()) {
            header.add(PlayerUi.pageHeader("Matches: " + event.name(),
                    "Lock the draw to create the matches.", editDraw));
            applyFilter();
            return;
        }
        Button shareDraw = new Button("Share draw", VaadinIcon.SHARE_SQUARE.create(), e -> shareDraw());
        shareDraw.setTooltipText("Build a message with the remaining matches, and an image of the draw,"
                + " with the courts and times entered below");
        header.add(PlayerUi.pageHeader("Matches: " + event.name(),
                "Assign officials, review entered results and confirm them to advance the winners.",
                editDraw, shareDraw));
        bracket.add(new Html(BracketSvg.render(knockout, null)));

        long confirmed = all.stream().filter(Match::confirmed).count();
        long awaiting = count(Match.Status.AWAITING_CONFIRMATION);
        long toPlay = count(Match.Status.SCHEDULED);
        long assigned = all.stream().filter(m -> !m.byeMatch() && m.official() != null).count();
        long needOfficial = all.stream().filter(m -> !m.byeMatch()).count();
        Match fin = all.getLast();
        stats.add(fin.confirmed() ? PlayerUi.statTile("Champion", fin.winnerName(), "event complete")
                        : PlayerUi.statTile("Progress", "In progress", "next: " + nextRound()),
                PlayerUi.statTile("Confirmed", String.valueOf(confirmed), "of " + all.size() + " matches"),
                PlayerUi.statTile("Awaiting confirmation", String.valueOf(awaiting),
                        awaiting > 0 ? "needs your review" : "nothing to review"),
                PlayerUi.statTile("To play", String.valueOf(toPlay), "both players known"),
                PlayerUi.statTile("Officials assigned", String.valueOf(assigned),
                        officials.isEmpty() ? "no officials available" : "of " + needOfficial + " matches"));
        applyFilter();
    }

    private long count(Match.Status status) {
        return all.stream().filter(m -> m.status() == status).count();
    }

    /** The earliest round that still has an unconfirmed match. */
    private String nextRound() {
        return all.stream().filter(m -> !m.confirmed()).findFirst().map(Match::roundName).orElse("—");
    }

    private void applyFilter() {
        Show filter = show.getValue() == null ? Show.ALL : show.getValue();
        List<Match> visible = all.stream()
                .filter(m -> filter.status == null || m.status() == filter.status).toList();
        grid.setItems(visible);
        showing.setText(all.isEmpty() ? "No matches yet"
                : visible.size() == all.size() ? all.size() + " matches"
                : "Showing " + visible.size() + " of " + all.size() + " matches");
    }
}
