package com.dijo.ui;

import com.dijo.events.DrawService;
import com.dijo.events.DrawSlot;
import com.dijo.events.Event;
import com.dijo.events.EventService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.ArrayList;
import java.util.List;

/** Admin fills an event's draw with players or BYEs, then locks it. */
@Route("admin/draw")
@RolesAllowed("ADMIN")
public class AdminDrawView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {
    /** A picker entry: a player, or BYE when player is null. */
    private record Choice(String player) {
        static final Choice BYE = new Choice(null);
        String label() { return player == null ? "BYE" : player; }
    }

    private final EventService events;
    private final DrawService draws;
    private final H1 heading = new H1("Draw");
    private final Span summary = new Span();
    private final Button lock = new Button("Lock draw", e -> lockDraw());
    private final Button unlock = new Button("Unlock draw", e -> confirmUnlock());
    private final Button clear = new Button("Clear draw", e -> confirmClear());
    private final Button viewMatches = new Button("View matches",
            e -> getUI().ifPresent(ui -> ui.navigate(AdminMatchesView.class, this.event.id())));
    private final Grid<DrawSlot> grid = new Grid<>(DrawSlot.class, false);
    private Event event;
    private List<String> unplaced = List.of();

    public AdminDrawView(EventService events, DrawService draws) {
        this.events = events;
        this.draws = draws;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        lock.addThemeVariants(ButtonVariant.PRIMARY);
        viewMatches.addThemeVariants(ButtonVariant.PRIMARY);
        clear.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);

        grid.addColumn(DrawSlot::slot).setHeader("Slot").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(this::playerPicker).setHeader("Player").setFlexGrow(1);
        grid.addComponentColumn(slot -> {
            Button remove = new Button("Remove", e -> {
                if (run(() -> draws.clearSlot(event.id(), slot.slot()))) reload();
            });
            remove.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
            remove.setEnabled(!slot.empty() && !event.drawLocked());
            return remove;
        }).setAutoWidth(true).setFlexGrow(0);
        grid.setSizeFull();

        HorizontalLayout status = new HorizontalLayout(summary, lock, viewMatches, unlock, clear);
        status.setAlignItems(Alignment.CENTER);
        status.getStyle().set("flex-wrap", "wrap");
        add(heading, status, grid);
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

    private ComboBox<Choice> playerPicker(DrawSlot slot) {
        List<Choice> choices = new ArrayList<>();
        choices.add(Choice.BYE);
        if (slot.playerUsername() != null) choices.add(new Choice(slot.playerUsername()));
        unplaced.forEach(player -> choices.add(new Choice(player)));

        ComboBox<Choice> picker = new ComboBox<>();
        picker.setItems(choices);
        picker.setItemLabelGenerator(Choice::label);
        picker.setValue(slot.bye() ? Choice.BYE
                : slot.playerUsername() == null ? null : new Choice(slot.playerUsername()));
        picker.setPlaceholder("Empty — choose a player or BYE");
        picker.setClearButtonVisible(true);
        picker.setReadOnly(event.drawLocked());
        picker.setWidthFull();
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

    private void reload() {
        if (event == null) {
            heading.setText("Draw");
            summary.setText("Event not found.");
            lock.setVisible(false);
            unlock.setVisible(false);
            clear.setVisible(false);
            viewMatches.setVisible(false);
            grid.setItems(List.of());
            return;
        }
        event = events.event(event.id()).orElse(event);  // pick up lock changes
        heading.setText("Draw: " + event.name());
        List<DrawSlot> slots = draws.draw(event.id());
        unplaced = draws.unplacedPlayers(event.id());
        grid.setItems(slots);

        long filled = slots.stream().filter(s -> !s.empty()).count();
        long byes = slots.stream().filter(DrawSlot::bye).count();
        boolean locked = event.drawLocked();
        lock.setVisible(!locked);
        unlock.setVisible(locked);
        viewMatches.setVisible(locked);
        clear.setVisible(!locked && filled > 0);
        summary.setText((locked ? "Locked • " : "")
                + filled + " of " + slots.size() + " slots filled"
                + (byes > 0 ? " (" + byes + (byes == 1 ? " BYE)" : " BYEs)") : "")
                + (locked ? "" : " • " + unplaced.size() + " signed-up players not yet placed"));
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
