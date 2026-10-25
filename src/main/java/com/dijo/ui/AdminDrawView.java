package com.dijo.ui;

import com.dijo.events.DrawService;
import com.dijo.events.DrawSlot;
import com.dijo.events.Event;
import com.dijo.events.EventService;
import com.dijo.events.EventUpdates;
import com.dijo.events.Player;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import java.util.ArrayList;
import java.util.List;

/** Admin fills an event's draw with players or BYEs, then locks it. */
@Route("admin/draw")
@RolesAllowed("ADMIN")
public class AdminDrawView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {
    /** A picker entry: a player (by username, shown by name), or BYE when player is null. */
    private record Choice(String player, String name) {
        static final Choice BYE = new Choice(null, null);
        String label() { return player == null ? "BYE" : name; }
        /** Picker text: the username after the name tells same-named players apart. */
        String pickerLabel() { return player == null || name.equals(player) ? label() : name + " (" + player + ")"; }
    }

    private final EventService events;
    private final DrawService draws;
    private final EventUpdates updates;
    private final Div header = new Div();
    private final Div stats = new Div();
    private final Grid<DrawSlot> grid = new Grid<>(DrawSlot.class, false);
    private Event event;
    private List<Player> unplaced = List.of();
    private Runnable unsubscribe;

    public AdminDrawView(EventService events, DrawService draws, EventUpdates updates) {
        this.events = events;
        this.draws = draws;
        this.updates = updates;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        RouterLink back = new RouterLink("← All events", AdminEventsView.class);
        back.addClassName("back-link");
        header.setWidthFull();
        stats.addClassName("stat-tiles");

        grid.addColumn(DrawSlot::slot).setHeader("Slot").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(this::playerPicker).setHeader("Player").setFlexGrow(1);
        grid.addComponentColumn(this::removeButton).setAutoWidth(true).setFlexGrow(0);
        // Shade every other first-round pair so each match reads as a unit.
        grid.setPartNameGenerator(slot -> ((slot.slot() + 1) / 2) % 2 == 0 ? "match-alt" : null);
        grid.addThemeVariants(GridVariant.NO_ROW_BORDERS);
        grid.setSizeFull();

        add(back, header, stats, grid);
        setFlexGrow(1, grid);
    }

    @Override
    public void setParameter(BeforeEvent beforeEvent, String eventId) {
        event = events.event(eventId).orElse(null);
        reload();
    }

    @Override
    public String getPageTitle() {
        return event == null ? "Draw" : "Draw: " + event.name();
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

    private ComboBox<Choice> playerPicker(DrawSlot slot) {
        List<Choice> choices = new ArrayList<>();
        choices.add(Choice.BYE);
        Choice current = slot.playerUsername() == null ? null
                : new Choice(slot.playerUsername(), slot.playerName());
        if (current != null) choices.add(current);
        unplaced.forEach(player -> choices.add(new Choice(player.username(), player.name())));

        ComboBox<Choice> picker = new ComboBox<>();
        picker.setItems(choices);
        picker.setItemLabelGenerator(Choice::pickerLabel);
        picker.setValue(slot.bye() ? Choice.BYE : current);
        picker.setPlaceholder("Choose a player or BYE");
        picker.setClearButtonVisible(true);
        picker.setReadOnly(event.drawLocked());
        picker.setWidthFull();
        picker.getStyle().set("max-width", "420px");
        picker.setAriaLabel("Player for slot " + slot.slot());
        picker.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            Choice choice = e.getValue();
            boolean ok = choice == null ? run(() -> draws.clearSlot(event.id(), slot.slot()))
                    : choice.equals(Choice.BYE) ? run(() -> draws.assignBye(event.id(), slot.slot()))
                    : run(() -> draws.assign(event.id(), slot.slot(), choice.player()));
            reload();
            if (ok && choice != null) notify(choice.label() + " placed in slot " + slot.slot());
        });
        return picker;
    }

    private Button removeButton(DrawSlot slot) {
        Button remove = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> {
            if (run(() -> draws.clearSlot(event.id(), slot.slot()))) reload();
        });
        remove.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        remove.setAriaLabel("Remove from slot " + slot.slot());
        remove.setTooltipText("Remove");
        remove.setEnabled(!slot.empty());
        remove.setVisible(!event.drawLocked());  // nothing to remove once locked
        return remove;
    }

    private void reload() {
        header.removeAll();
        stats.removeAll();
        if (event == null) {
            header.add(PlayerUi.pageHeader("Draw", "This event was not found."));
            grid.setItems(List.of());
            return;
        }
        event = events.event(event.id()).orElse(event);  // pick up lock changes
        List<DrawSlot> slots = draws.draw(event.id());
        unplaced = draws.unplacedPlayers(event.id());
        grid.setItems(slots);

        long filled = slots.stream().filter(s -> !s.empty()).count();
        long byes = slots.stream().filter(DrawSlot::bye).count();
        boolean locked = event.drawLocked();
        if (locked) {
            Button unlock = new Button("Unlock", VaadinIcon.UNLOCK.create(), e -> confirmUnlock());
            Button matches = new Button("View matches", VaadinIcon.ARROW_RIGHT.create(),
                    e -> getUI().ifPresent(ui -> ui.navigate(AdminMatchesView.class, event.id())));
            matches.setIconAfterText(true);
            matches.addThemeVariants(ButtonVariant.PRIMARY);
            header.add(PlayerUi.pageHeader("Draw: " + event.name(),
                    "The draw is locked and its matches are created. Unlock it to make changes.",
                    unlock, matches));
        } else {
            Button clear = new Button("Clear draw", e -> confirmClear());
            clear.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            clear.setVisible(filled > 0);
            Button lock = new Button("Lock draw", VaadinIcon.LOCK.create(), e -> lockDraw());
            lock.addThemeVariants(ButtonVariant.PRIMARY);
            header.add(PlayerUi.pageHeader("Draw: " + event.name(),
                    "Place a signed-up player or a BYE in every slot, then lock the draw.",
                    clear, lock));
        }

        stats.add(PlayerUi.statTile("Status", locked ? "Locked" : "Open",
                        locked ? "matches created" : filled == slots.size() ? "ready to lock" : "being filled"),
                PlayerUi.statTile("Slots filled", String.valueOf(filled), "of " + slots.size() + " slots"),
                PlayerUi.statTile("Players placed", String.valueOf(filled - byes), null),
                PlayerUi.statTile("BYEs", String.valueOf(byes), null),
                PlayerUi.statTile("Still to place", String.valueOf(unplaced.size()), "signed-up players"));
    }

    private void lockDraw() {
        if (run(() -> draws.lock(event.id()))) {
            notify("Draw locked");
            reload();
        }
    }

    private void confirmUnlock() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Unlock the draw?");
        confirm.setText("Its matches will be removed, and the draw can be changed again"
                + " until it is locked.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Unlock");
        confirm.addConfirmListener(e -> {
            if (run(() -> draws.unlock(event.id()))) {
                notify("Draw unlocked");
                reload();
            }
        });
        confirm.open();
    }

    private void confirmClear() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Clear the draw?");
        confirm.setText("All players and BYEs will be removed from their slots.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Clear draw");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            if (run(() -> draws.clearDraw(event.id()))) {
                notify("Draw cleared");
                reload();
            }
        });
        confirm.open();
    }

    private boolean run(Runnable action) {
        try {
            action.run();
            return true;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.ERROR);
            return false;
        }
    }

    private static void notify(String message) {
        Notification.show(message, 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.SUCCESS);
    }
}
