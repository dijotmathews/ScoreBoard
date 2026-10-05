package com.dijo.ui;

import com.dijo.benefits.PlayerAccountService;
import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.benefits.PlayerBenefitsService.Coupon;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import java.math.BigDecimal;

/** Create a player, or view/update/delete the selected one and manage their benefits. */
class PlayerEditorDialog extends Dialog {
    private final PlayerAccountService accounts;
    private final PlayerBenefitsService benefits;
    private final PlayerBenefitRow player;
    private final Runnable onChange;

    private final TextField username = new TextField("Username");
    private final PasswordField password = new PasswordField();
    private final Checkbox enabled = new Checkbox("Account enabled", true);
    private final VerticalLayout benefitsPanel = new VerticalLayout();

    /** player == null opens the dialog in create mode. */
    PlayerEditorDialog(PlayerAccountService accounts, PlayerBenefitsService benefits,
            PlayerBenefitRow player, Runnable onChange) {
        this.accounts = accounts;
        this.benefits = benefits;
        this.player = player;
        this.onChange = onChange;
        boolean creating = player == null;

        setHeaderTitle(creating ? "New player" : "Player: " + player.username());
        setWidth("min(640px, 100vw)");

        password.setLabel(creating ? "Password" : "New password");
        password.setHelperText(creating ? "At least 8 characters"
                : "Leave blank to keep the current password");
        if (!creating) {
            username.setValue(player.username());
            username.setReadOnly(true);
            enabled.setValue(player.enabled());
        }
        FormLayout form = new FormLayout(username, password, enabled);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("400px", 2));
        form.setColspan(enabled, 2);
        add(form);

        if (!creating) {
            benefitsPanel.setPadding(false);
            add(benefitsPanel);
            renderBenefits();
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
            if (player == null) {
                accounts.createPlayer(username.getValue().strip(), password.getValue(),
                        enabled.getValue());
            } else {
                accounts.updatePlayer(player.username(), enabled.getValue(), password.getValue());
            }
        });
        if (ok) {
            notify(player == null ? "Player created" : "Player updated");
            onChange.run();
            close();
        }
    }

    private void confirmDelete() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete " + player.username() + "?");
        confirm.setText("The account and its roles will be removed permanently.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            if (run(() -> accounts.deletePlayer(player.username()))) {
                notify("Player deleted");
                onChange.run();
                close();
            }
        });
        confirm.open();
    }

    private void renderBenefits() {
        benefitsPanel.removeAll();
        benefitsPanel.add(new H3("Sign-up and benefits"));

        Benefits current = null;
        try {
            current = benefits.playerBenefits(player.username());
        } catch (IllegalArgumentException notSignedUp) {
            // No confirmed entry payment yet.
        }

        if (current == null) {
            var settings = benefits.settings().orElse(null);
            if (settings == null) {
                benefitsPanel.add(new Span(
                        "Not signed up. Set the entry fee in Benefit settings to record payments."));
                return;
            }
            TextField reference = new TextField("Transaction ID");
            Button pay = new Button("Record payment " + rupees(settings.feePaise()), e -> {
                if (run(() -> benefits.confirmPayment(player.username(),
                        reference.getValue().strip(), settings.feePaise()))) {
                    notify("Payment recorded; player signed up");
                    changedBenefits();
                }
            });
            HorizontalLayout payRow = new HorizontalLayout(reference, pay);
            payRow.setAlignItems(FlexComponent.Alignment.END);
            payRow.getStyle().set("flex-wrap", "wrap");
            benefitsPanel.add(new Span("Not signed up: entry fee not paid."), payRow);
            return;
        }

        benefitsPanel.add(new Span("Signed up • Paid " + rupees(current.amountPaise())
                + " • Transaction ID: " + current.paymentReference()));
        benefitsPanel.add(paymentEditor(current));

        if (current.kitHandedOverAt() == null) {
            Button handOver = new Button("Hand over welcome kit", e -> {
                if (run(() -> benefits.handOverKit(player.username()))) {
                    notify("Welcome kit handed over");
                    changedBenefits();
                }
            });
            benefitsPanel.add(new HorizontalLayout(new Span("Welcome kit pending."), handOver));
        } else {
            benefitsPanel.add(new Span("Welcome kit collected at " + current.kitHandedOverAt()));
        }

        if (!current.coupons().isEmpty()) {
            Grid<Coupon> coupons = new Grid<>(Coupon.class, false);
            coupons.addColumn(Coupon::description).setHeader("Coupon").setAutoWidth(true);
            coupons.addColumn(c -> c.redeemedAt() == null ? "Available" : "Redeemed")
                    .setHeader("Status").setAutoWidth(true);
            coupons.addComponentColumn(c -> {
                Button redeem = new Button("Redeem", e -> {
                    if (run(() -> benefits.redeemCoupon(player.username(), c.code()))) {
                        notify("Coupon redeemed");
                        changedBenefits();
                    }
                });
                redeem.addThemeVariants(ButtonVariant.SMALL);
                redeem.setEnabled(c.redeemedAt() == null);
                return redeem;
            });
            coupons.setItems(current.coupons());
            coupons.setAllRowsVisible(true);
            benefitsPanel.add(coupons);
        }
    }

    /** Updating a payment always requires a new transaction ID. */
    private HorizontalLayout paymentEditor(Benefits current) {
        BigDecimalField amount = new BigDecimalField("Amount (₹)");
        amount.setValue(BigDecimal.valueOf(current.amountPaise(), 2));
        TextField transactionId = new TextField("New transaction ID");
        transactionId.setHelperText("Required for every update");
        Button update = new Button("Update payment", e -> {
            if (run(() -> benefits.updatePayment(player.username(),
                    transactionId.getValue().strip(), paise(amount.getValue())))) {
                notify("Payment updated");
                changedBenefits();
            }
        });
        HorizontalLayout row = new HorizontalLayout(amount, transactionId, update);
        row.setAlignItems(FlexComponent.Alignment.END);
        row.getStyle().set("flex-wrap", "wrap");
        return row;
    }

    static long paise(BigDecimal rupees) {
        if (rupees == null || rupees.signum() <= 0 || rupees.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Enter a positive amount with at most 2 decimals");
        }
        return rupees.movePointRight(2).longValueExact();
    }

    private void changedBenefits() {
        onChange.run();
        renderBenefits();
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

    static String rupees(long paise) {
        return "₹" + BigDecimal.valueOf(paise, 2).toPlainString();
    }
}
