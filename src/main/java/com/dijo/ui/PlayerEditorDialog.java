package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;
import static com.dijo.ui.PlayerUi.muted;
import static com.dijo.ui.PlayerUi.rows;

import com.dijo.benefits.PlayerAccountService;
import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.benefits.PlayerBenefitsService.Coupon;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.math.BigDecimal;
import java.util.List;

/** Create a player, or view/update/delete the selected one and manage their benefits. */
class PlayerEditorDialog extends Dialog {
    private final PlayerAccountService accounts;
    private final PlayerBenefitsService benefits;
    private final PlayerBenefitRow player;
    private final Runnable onChange;

    private final TextField username = new TextField("Username");
    private final TextField fullName = new TextField("Full name");
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

        setHeaderTitle(creating ? "New player" : "Player: " + player.name());
        setWidth("min(640px, 100vw)");

        fullName.setPlaceholder("e.g. Dijo T Mathews");
        username.setReadOnly(true);
        FormLayout form = new FormLayout();
        if (creating) {
            // The username, and the initial password, come from the full name.
            fullName.setRequiredIndicatorVisible(true);
            fullName.setHelperText("Shown in draws and matches");
            fullName.setValueChangeMode(ValueChangeMode.EAGER);
            fullName.addValueChangeListener(e -> username.setValue(previewUsername(e.getValue())));
            username.setHelperText("Also the initial password; a number is added if it is taken");
            form.add(fullName, username, enabled);
        } else {
            fullName.setHelperText("Shown in draws and matches; the username if left blank");
            fullName.setValue(player.fullName() == null ? "" : player.fullName());
            username.setValue(player.username());
            password.setLabel("New password");
            password.setHelperText("Leave blank to keep the current password");
            enabled.setValue(player.enabled());
            form.add(fullName, username, password, enabled);
        }
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("400px", 2));
        form.setColspan(fullName, 2);
        form.setColspan(enabled, 2);
        add(form);

        if (!creating) {
            benefitsPanel.setPadding(false);
            benefitsPanel.addClassName("benefits-panel");
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
        String[] created = new String[1];
        boolean ok = run(() -> {
            if (player == null) {
                created[0] = accounts.createPlayer(fullName.getValue(), enabled.getValue());
            } else {
                accounts.updatePlayer(player.username(), fullName.getValue(), enabled.getValue(),
                        password.getValue());
            }
        });
        if (ok) {
            if (created[0] == null) {
                notify("Player updated");
            } else {
                // Kept on screen longer so the admin can pass the sign-in details on.
                Notification.show("Player created. Username and initial password: " + created[0],
                        8000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.SUCCESS);
            }
            onChange.run();
            close();
        }
    }

    /** The username a full name gives, before any number is added; blank while it gives none. */
    private static String previewUsername(String name) {
        try {
            return PlayerAccountService.usernameFor(name.strip());
        } catch (IllegalArgumentException tooShort) {
            return "";
        }
    }

    private void confirmDelete() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete " + player.name() + "?");
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
            benefitsPanel.add(signUpCard());
            return;
        }
        benefitsPanel.add(paymentCard(current), kitCard(current), couponsCard(current));
    }

    /** Not signed up: record a verified entry fee payment, which signs the player up. */
    private Card signUpCard() {
        Card card = card("Entry fee", badge("Not paid", BadgeVariant.WARNING));
        var settings = benefits.settings().orElse(null);
        if (settings == null) {
            card.add(muted("Set the entry fee in Benefit settings to record payments."));
            return card;
        }
        TextField reference = new TextField("Transaction ID");
        reference.setPlaceholder("e.g. UPI reference");
        reference.setWidthFull();
        Button pay = new Button("Record payment " + rupees(settings.feePaise()), e -> {
            if (run(() -> benefits.confirmPayment(player.username(),
                    reference.getValue().strip(), settings.feePaise()))) {
                notify("Payment recorded; player signed up");
                changedBenefits();
            }
        });
        pay.addThemeVariants(ButtonVariant.PRIMARY);
        card.add(muted("Record the payment once it has been verified. This signs the player up "
                + "and issues their welcome kit and coupons."), fieldRow(reference, pay));
        return card;
    }

    /** Paid: the payment details, with a correction form behind a button. */
    private Card paymentCard(Benefits current) {
        Card card = card("Entry fee", badge("Paid", BadgeVariant.SUCCESS));
        card.add(rows(
                "Amount", rupees(current.amountPaise()),
                "Transaction ID", current.paymentReference(),
                "Paid on", PlayerUi.dateTime(current.confirmedAt())));

        Div editor = paymentEditor(current);
        editor.setVisible(false);
        Button correct = new Button("Correct payment", VaadinIcon.EDIT.create());
        correct.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
        correct.addClickListener(e -> {
            editor.setVisible(true);
            correct.setVisible(false);
        });
        card.add(editor);
        card.addToFooter(correct);
        return card;
    }

    /** Updating a payment always requires a new transaction ID. */
    private Div paymentEditor(Benefits current) {
        BigDecimalField amount = new BigDecimalField("Amount (₹)");
        amount.setValue(BigDecimal.valueOf(current.amountPaise(), 2));
        amount.setWidth("8em");
        TextField transactionId = new TextField("New transaction ID");
        transactionId.setHelperText("Required for every change");
        transactionId.setWidthFull();
        Button update = new Button("Update payment", e -> {
            if (run(() -> benefits.updatePayment(player.username(),
                    transactionId.getValue().strip(), paise(amount.getValue())))) {
                notify("Payment updated");
                changedBenefits();
            }
        });
        update.addThemeVariants(ButtonVariant.PRIMARY);
        return fieldRow(amount, transactionId, update);
    }

    private Card kitCard(Benefits current) {
        boolean collected = current.kitHandedOverAt() != null;
        Card card = card("Welcome kit", collected ? badge("Collected", BadgeVariant.SUCCESS)
                : badge("Ready to collect", BadgeVariant.WARNING));
        if (collected) {
            card.add(muted("Collected on " + PlayerUi.dateTime(current.kitHandedOverAt()) + "."));
            return card;
        }
        Button handOver = new Button("Hand over kit", e -> {
            if (run(() -> benefits.handOverKit(player.username()))) {
                notify("Welcome kit handed over");
                changedBenefits();
            }
        });
        handOver.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.PRIMARY);
        Div list = PlayerUi.list();
        list.add(listRow("Not collected yet", null, handOver));
        card.add(list);
        return card;
    }

    private Card couponsCard(Benefits current) {
        List<Coupon> coupons = current.coupons();
        long available = coupons.stream().filter(c -> c.redeemedAt() == null).count();
        Card card = card("Coupons", badge(available + " of " + coupons.size() + " available",
                available > 0 ? BadgeVariant.SUCCESS : BadgeVariant.CONTRAST));
        if (coupons.isEmpty()) {
            card.add(muted("No coupons were issued with this sign-up."));
            return card;
        }
        Div list = PlayerUi.list();
        for (Coupon c : coupons) list.add(couponRow(c));
        card.add(list);
        return card;
    }

    /** A coupon with its code; a Redeem button while available, else when it was used. */
    private Div couponRow(Coupon coupon) {
        boolean used = coupon.redeemedAt() != null;
        Span sub = new Span(PlayerUi.couponCode(coupon.code()));
        if (used) sub.add(new Text(" · redeemed " + PlayerUi.dateTime(coupon.redeemedAt())));
        Component end;
        if (used) {
            end = badge("Redeemed", BadgeVariant.CONTRAST);
        } else {
            Button redeem = new Button("Redeem", e -> {
                if (run(() -> benefits.redeemCoupon(player.username(), coupon.code()))) {
                    notify("Coupon redeemed");
                    changedBenefits();
                }
            });
            redeem.addThemeVariants(ButtonVariant.SMALL);
            end = redeem;
        }
        Div row = listRow(coupon.description(), sub, end);
        if (used) row.addClassName("dim");
        return row;
    }

    /** Fields and a button on one line, wrapping on narrow screens. */
    private static Div fieldRow(Component... parts) {
        Div row = new Div(parts);
        row.addClassName("field-row");
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
