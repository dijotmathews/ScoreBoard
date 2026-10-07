package com.dijo.ui;

import com.dijo.events.OfficialService;
import com.dijo.events.OfficialService.Official;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;

/** Create an official, or update/delete the selected one. */
class OfficialEditorDialog extends Dialog {
    private final OfficialService service;
    private final Official official;
    private final Runnable onChange;

    private final TextField username = new TextField("Username");
    private final PasswordField password = new PasswordField();
    private final Checkbox enabled = new Checkbox("Account enabled", true);

    /** official == null opens the dialog in create mode. */
    OfficialEditorDialog(OfficialService service, Official official, Runnable onChange) {
        this.service = service;
        this.official = official;
        this.onChange = onChange;
        boolean creating = official == null;

        setHeaderTitle(creating ? "New official" : "Official: " + official.username());
        setWidth("min(520px, 100vw)");

        password.setLabel(creating ? "Password" : "New password");
        password.setHelperText(creating ? "At least 8 characters"
                : "Leave blank to keep the current password");
        if (!creating) {
            username.setValue(official.username());
            username.setReadOnly(true);
            enabled.setValue(official.enabled());
        }
        FormLayout form = new FormLayout(username, password, enabled);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("400px", 2));
        form.setColspan(enabled, 2);
        add(form);

        if (!creating) {
            add(PlayerUi.rows("Matches assigned", String.valueOf(official.assigned()),
                    "To score", String.valueOf(official.open()),
                    "Completed", String.valueOf(official.completed())));
            if (official.open() > 0) {
                add(PlayerUi.muted("Disabling this account keeps their " + official.open()
                        + (official.open() == 1 ? " open match" : " open matches")
                        + " assigned; reassign them on each event's Matches page."));
            }
        }

        Button save = new Button(creating ? "Create" : "Save", e -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Button cancel = new Button("Cancel", e -> close());
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        if (!creating) {
            Button delete = new Button("Delete", e -> confirmDelete());
            delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            delete.getStyle().set("margin-inline-end", "auto");
            getFooter().add(delete);
        }
        getFooter().add(cancel, save);
    }

    private void save() {
        boolean ok = run(() -> {
            if (official == null) {
                service.createOfficial(username.getValue().strip(), password.getValue(),
                        enabled.getValue());
            } else {
                service.updateOfficial(official.username(), enabled.getValue(), password.getValue());
            }
        });
        if (ok) done(official == null ? "Official created" : "Official updated");
    }

    private void confirmDelete() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete " + official.username() + "?");
        confirm.setText("The account will be removed permanently.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            if (run(() -> service.deleteOfficial(official.username()))) done("Official deleted");
        });
        confirm.open();
    }

    private void done(String message) {
        Notification.show(message, 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.SUCCESS);
        onChange.run();
        close();
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
}
