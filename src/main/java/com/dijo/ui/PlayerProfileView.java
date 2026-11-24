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
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.Locale;

/** The player's account, contact number and tournament benefits. */
@Route("player/profile")
@PageTitle("My Profile")
@RolesAllowed("USER")
public class PlayerProfileView extends MobileHomeLayout {
    public PlayerProfileView(PlayerBenefitsService benefits, PlayerAccountService accounts) {
        super("My Profile");

        Benefits mine;
        try {
            mine = benefits.myBenefits();
        } catch (IllegalArgumentException notSignedUp) {
            mine = null;
        }

        String name = benefits.myName();
        add(PlayerUi.header(name, name, mine != null), contactCard(accounts), new H3("My Benefits"));
        if (mine == null) {
            Card card = card("Sign up", badge("Not signed up", BadgeVariant.WARNING));
            card.add(muted(benefits.settings()
                    .map(s -> "Pay the entry fee of " + PlayerEditorDialog.rupees(s.feePaise())
                            + " to sign up. You'll receive a welcome kit and "
                            + s.couponCount() + " " + s.couponDescription().toLowerCase(Locale.ROOT)
                            + (s.couponCount() == 1 ? " coupon." : " coupons."))
                    .orElse("Sign-ups have not opened yet.")));
            add(card);
            return;
        }

        Card payment = card("Entry fee", badge("Paid", BadgeVariant.SUCCESS));
        payment.add(rows(
                "Amount", PlayerEditorDialog.rupees(mine.amountPaise()),
                "Transaction ID", mine.paymentReference(),
                "Paid on", PlayerUi.dateTime(mine.confirmedAt())));

        boolean collected = mine.kitHandedOverAt() != null;
        Card kit = card("Welcome kit", collected ? badge("Collected", BadgeVariant.SUCCESS)
                : badge("Ready to collect", BadgeVariant.WARNING));
        kit.add(muted(collected
                ? "Collected on " + PlayerUi.dateTime(mine.kitHandedOverAt()) + "."
                : "Show this page to an official to collect your kit."));

        long available = mine.coupons().stream().filter(c -> c.redeemedAt() == null).count();
        Card coupons = card("Coupons", badge(available + " of " + mine.coupons().size() + " available",
                available > 0 ? BadgeVariant.SUCCESS : BadgeVariant.CONTRAST));
        if (mine.coupons().isEmpty()) {
            coupons.add(muted("No coupons are included with your sign-up."));
        } else {
            Div list = PlayerUi.list();
            mine.coupons().forEach(c -> list.add(couponRow(c)));
            coupons.add(list);
        }

        add(payment, kit, coupons);
    }

    /** The player's own contact number, which they can add, change or clear. */
    private static Card contactCard(PlayerAccountService accounts) {
        String current = accounts.myPhone();
        Card card = card("Contact number", status(current));
        TextField phone = new TextField("Your number");
        phone.setValue(current == null ? "" : current);
        phone.setPlaceholder("e.g. +91 98765 43210");
        phone.setHelperText("Optional. Organisers use it to reach you about your matches.");
        phone.setWidthFull();
        Button save = new Button("Save", e -> {
            try {
                String saved = accounts.updateMyPhone(phone.getValue());
                phone.setValue(saved == null ? "" : saved);
                card.setHeaderSuffix(status(saved));
                Notification.show(saved == null ? "Contact number removed" : "Contact number saved",
                        3000, Notification.Position.BOTTOM_START).addThemeVariants(NotificationVariant.SUCCESS);
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.ERROR);
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Div row = new Div(phone, save);
        row.addClassName("field-row");
        card.add(row);
        return card;
    }

    private static Badge status(String phone) {
        return phone == null ? badge("Not added") : badge("Added", BadgeVariant.SUCCESS);
    }

    private static Div couponRow(Coupon coupon) {
        boolean used = coupon.redeemedAt() != null;
        Div row = listRow(coupon.description(), PlayerUi.couponCode(coupon.code()), used ? badge("Redeemed", BadgeVariant.CONTRAST)
                : badge("Available", BadgeVariant.SUCCESS));
        if (used) row.addClassName("dim");
        return row;
    }

}
