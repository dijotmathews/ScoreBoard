package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;
import static com.dijo.ui.PlayerUi.card;
import static com.dijo.ui.PlayerUi.listRow;
import static com.dijo.ui.PlayerUi.muted;
import static com.dijo.ui.PlayerUi.rows;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.benefits.PlayerBenefitsService.Coupon;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.Locale;

/** The player's account and tournament benefits. */
@Route("player/profile")
@PageTitle("My Profile")
@RolesAllowed("USER")
public class PlayerProfileView extends MobileHomeLayout {
    public PlayerProfileView(PlayerBenefitsService benefits) {
        super("My Profile");

        Benefits mine;
        try {
            mine = benefits.myBenefits();
        } catch (IllegalArgumentException notSignedUp) {
            mine = null;
        }

        String name = benefits.myName();
        add(PlayerUi.header(name, name, mine != null), new H3("My Benefits"));
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

    private static Div couponRow(Coupon coupon) {
        boolean used = coupon.redeemedAt() != null;
        Div row = listRow(coupon.description(), PlayerUi.couponCode(coupon.code()), used ? badge("Redeemed", BadgeVariant.CONTRAST)
                : badge("Available", BadgeVariant.SUCCESS));
        if (used) row.addClassName("dim");
        return row;
    }

}
