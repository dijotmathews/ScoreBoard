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
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin view of an event's knockout: assign officials, enter and confirm results.
 * Refreshes itself (server push) when results for this event change elsewhere.
 * Court and time are typed in only to build a shareable schedule message; they are
 * kept in this view instance and never saved.
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
    private List<Match> all = List.of();
    private List<String> officials = List.of();
    private Runnable unsubscribe;
    /** Court and time per match, keyed by "round-matchNo". In memory only, never persisted. */
    private final Map<String, Slot> slots = new HashMap<>();

    private record Slot(String court, LocalTime time) {
        boolean isEmpty() { return (court == null || court.isBlank()) && time == null; }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

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
        Button share = new Button("Share schedule", VaadinIcon.SHARE.create(), e -> shareSchedule());
        share.setTooltipText("Build a message and a draw image with the courts and times entered below");
        Div toolbar = new Div(show, share, showing);
        toolbar.addClassName("grid-toolbar");

        grid.addColumn(m -> m.roundName() + " · " + m.matchNo()).setHeader("Match").setAutoWidth(true);
        grid.addColumn(m -> m.side1() + " vs " + m.side2()).setHeader("Players").setFlexGrow(2);
        grid.addComponentColumn(this::officialCell).setHeader("Official").setWidth("200px").setFlexGrow(0);
        grid.addComponentColumn(this::courtCell).setHeader("Court").setWidth("110px").setFlexGrow(0);
        grid.addComponentColumn(this::timeCell).setHeader("Time").setWidth("140px").setFlexGrow(0);
        grid.addColumn(m -> m.winner() == null ? "—"
                : m.winner() + (m.score() == null ? "" : " · " + m.score()))
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

    private static String key(Match m) {
        return m.round() + "-" + m.matchNo();
    }

    private Slot slot(Match m) {
        return slots.getOrDefault(key(m), new Slot(null, null));
    }

    private boolean schedulable(Match m) {
        return !m.byeMatch() && !m.confirmed();
    }

    private Component courtCell(Match match) {
        if (!schedulable(match)) return PlayerUi.muted("—");
        TextField court = new TextField();
        court.setValue(slot(match).court() == null ? "" : slot(match).court());
        court.setPlaceholder("Court");
        court.setWidthFull();
        court.setAriaLabel("Court for " + match.roundName() + " match " + match.matchNo());
        court.addValueChangeListener(e -> slots.put(key(match), new Slot(e.getValue(), slot(match).time())));
        return court;
    }

    private Component timeCell(Match match) {
        if (!schedulable(match)) return PlayerUi.muted("—");
        TimePicker time = new TimePicker();
        time.setStep(Duration.ofMinutes(15));
        time.setValue(slot(match).time());
        time.setPlaceholder("Time");
        time.setWidthFull();
        time.setAriaLabel("Time for " + match.roundName() + " match " + match.matchNo());
        time.addValueChangeListener(e -> slots.put(key(match), new Slot(slot(match).court(), e.getValue())));
        return time;
    }

    /**
     * Opens a ready-to-send message listing every open match with a court or time
     * entered, plus a picture of the draw with those courts and times on it.
     */
    private void shareSchedule() {
        if (event == null || all.isEmpty()) {
            Notification.show("Lock the draw to create the matches first", 4000, Notification.Position.MIDDLE);
            return;
        }
        List<Match> scheduled = all.stream()
                .filter(m -> schedulable(m) && !slot(m).isEmpty())
                .sorted(Comparator.comparing((Match m) -> slot(m).time(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String text = null;
        if (!scheduled.isEmpty()) {
            StringBuilder b = new StringBuilder("*" + event.name() + " – Match schedule*\n");
            for (Match m : scheduled) {
                b.append("\n").append(m.roundName()).append(" ").append(m.matchNo()).append(": ")
                        .append(m.side1()).append(" vs ").append(m.side2());
                String where = slotLabel(slot(m));
                if (where != null) b.append(" – ").append(where);
            }
            text = b.toString();
        }
        String svg = BracketSvg.image(all, event.name(), m -> schedulable(m) ? slotLabel(slot(m)) : null);
        String file = event.name().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "") + "-draw.png";
        new ShareMessageDialog("Share schedule", text, svg, file).open();
    }

    /** "Court 3 · 10:30 AM", "Court 3" or "10:30 AM"; null when neither is set. */
    private static String slotLabel(Slot s) {
        boolean hasCourt = s.court() != null && !s.court().isBlank();
        if (!hasCourt) return s.time() == null ? null : s.time().format(TIME);
        return "Court " + s.court().trim() + (s.time() == null ? "" : " · " + s.time().format(TIME));
    }

    private void reload() {
        header.removeAll();
        stats.removeAll();
        bracket.removeAll();
        if (event == null) {
            header.add(PlayerUi.pageHeader("Matches", "This event was not found."));
            all = List.of();
            applyFilter();
            return;
        }
        event = events.event(event.id()).orElse(event);
        Button editDraw = new Button("Edit draw", VaadinIcon.EDIT.create(),
                e -> getUI().ifPresent(ui -> ui.navigate(AdminDrawView.class, event.id())));
        all = matches.matches(event.id());
        officials = matches.officials();

        if (all.isEmpty()) {
            header.add(PlayerUi.pageHeader("Matches: " + event.name(),
                    "Lock the draw to create the matches.", editDraw));
            applyFilter();
            return;
        }
        header.add(PlayerUi.pageHeader("Matches: " + event.name(),
                "Assign officials, review entered results and confirm them to advance the winners.",
                editDraw));
        bracket.add(new Html(BracketSvg.render(all, null)));

        long confirmed = all.stream().filter(Match::confirmed).count();
        long awaiting = count(Match.Status.AWAITING_CONFIRMATION);
        long toPlay = count(Match.Status.SCHEDULED);
        long assigned = all.stream().filter(m -> !m.byeMatch() && m.official() != null).count();
        long needOfficial = all.stream().filter(m -> !m.byeMatch()).count();
        Match fin = all.getLast();
        stats.add(fin.confirmed() ? PlayerUi.statTile("Champion", fin.winner(), "event complete")
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
