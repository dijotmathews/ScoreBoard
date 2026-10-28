package com.dijo.ui;

import com.dijo.events.EventService;
import com.dijo.events.EventSummary;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;

/** Create an event, or update/delete the selected one. */
class EventEditorDialog extends Dialog {
    private static final String SINGLES = "Singles";
    private static final String DOUBLES = "Doubles";

    private final EventService service;
    private final EventSummary current;
    private final Runnable onChange;

    private final TextField name = new TextField("Event name");
    private final Select<Integer> contestants = new Select<>();
    private final Select<String> type = new Select<>();

    /** current == null opens the dialog in create mode. */
    EventEditorDialog(EventService service, EventSummary current, Runnable onChange) {
        this.service = service;
        this.current = current;
        this.onChange = onChange;
        boolean creating = current == null;

        setHeaderTitle(creating ? "New event" : "Event: " + current.event().name());
        setWidth("min(560px, 100vw)");

        type.setLabel("Type");
        type.setItems(SINGLES, DOUBLES);
        type.setHelperText(creating ? "Doubles teams have two players; this cannot be changed later"
                : "Fixed when the event was created");
        type.setReadOnly(!creating);

        contestants.setLabel("Contestants (draw size)");
        contestants.setItems(EventService.CONTESTANT_SIZES);
        contestants.setHelperText(creating ? "An empty draw of this size is created"
                : "Changing it rebuilds the empty draw");

        if (creating) {
            type.setValue(SINGLES);
            contestants.setValue(16);
        } else {
            var event = current.event();
            name.setValue(event.name());
            type.setValue(event.doubles() ? DOUBLES : SINGLES);
            contestants.setValue(event.contestantCount());
            if (current.filled() > 0) {
                contestants.setReadOnly(true);
                contestants.setHelperText("Clear the draw to change the number of contestants");
            }
        }

        FormLayout form = new FormLayout(name, type, contestants);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("400px", 2));
        form.setColspan(name, 2);
        add(form);

        Button save = new Button(creating ? "Create" : "Save", e -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Button cancel = new Button("Cancel", e -> close());
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        if (!creating) {
            Button delete = new Button("Delete", e -> confirmDelete());
            delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            delete.getStyle().set("margin-inline-end", "auto");
            Button draw = new Button("Open draw", e -> {
                close();
                getUI().ifPresent(ui -> ui.navigate(AdminDrawView.class, current.event().id()));
            });
            getFooter().add(delete, draw);
        }
        getFooter().add(cancel, save);
    }

    private void save() {
        boolean ok = run(() -> {
            if (contestants.getValue() == null) {
                throw new IllegalArgumentException("Choose the number of contestants");
            }
            if (current == null) {
                service.createEvent(name.getValue().strip(), contestants.getValue(),
                        DOUBLES.equals(type.getValue()));
            } else {
                service.updateEvent(current.event().id(), name.getValue().strip(),
                        contestants.getValue());
            }
        });
        if (ok) {
            notify(current == null ? "Event created" : "Event updated");
            onChange.run();
            close();
        }
    }

    private void confirmDelete() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete " + current.event().name() + "?");
        confirm.setText("The event and its draw will be removed permanently.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            if (run(() -> service.deleteEvent(current.event().id()))) {
                notify("Event deleted");
                onChange.run();
                close();
            }
        });
        confirm.open();
    }

    /** Runs a service call, reporting validation errors; returns true on success. */
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
