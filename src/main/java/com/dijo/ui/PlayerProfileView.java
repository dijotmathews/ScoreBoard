package com.dijo.ui;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.benefits.PlayerBenefitsService.Coupon;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** The player's account and tournament benefits. */
@Route("player/profile")
@PageTitle("My Profile")
@RolesAllowed("USER")
public class PlayerProfileView extends MobileHomeLayout {
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.systemDefault());

    private final PlayerBenefitsService benefits;

    public PlayerProfileView(PlayerBenefitsService benefits, AuthenticationContext authentication) {
        super("My Profile");
        this.benefits = benefits;
        add(new Span("Signed in as " + authentication.getPrincipalName().orElse("")),
                new H3("My Benefits"), benefitsCard());
    }

    private Card benefitsCard() {
        Card card = new Card();
        card.setWidthFull();
        card.setTitle("Tournament sign-up");

        Benefits mine;
        try {
            mine = benefits.myBenefits();
        } catch (IllegalArgumentException notSignedUp) {
            card.setSubtitle("Not signed up");
            card.add(new Span(benefits.settings()
                    .map(s -> "Pay the entry fee of " + PlayerEditorDialog.rupees(s.feePaise())
                            + " to sign up and receive your welcome kit and "
                            + s.couponCount() + " coupons.")
                    .orElse("Sign-ups have not opened yet.")));
            return card;
        }

        card.setSubtitle("Signed up");
        VerticalLayout details = new VerticalLayout(
                new Span("Amount paid: " + PlayerEditorDialog.rupees(mine.amountPaise())),
                new Span("Transaction ID: " + mine.paymentReference()),
                new Span("Welcome kit: " + (mine.kitHandedOverAt() == null
                        ? "Ready to collect"
                        : "Collected on " + WHEN.format(Instant.parse(mine.kitHandedOverAt())))));
        details.setPadding(false);
        details.setSpacing(false);

        long remaining = mine.coupons().stream().filter(c -> c.redeemedAt() == null).count();
        details.add(new Span("Coupons: " + remaining + " of " + mine.coupons().size() + " available"));
        for (Coupon coupon : mine.coupons()) {
            details.add(couponLine(coupon));
        }
        card.add(details);
        return card;
    }

    private Span couponLine(Coupon coupon) {
        Span code = new Span(coupon.code());
        code.getStyle().set("font-family", "monospace").set("font-size", "0.85em");
        Span line = new Span(new Span("• " + coupon.description() + " ("
                + (coupon.redeemedAt() == null ? "available" : "redeemed") + "): "), code);
        if (coupon.redeemedAt() != null) {
            line.getStyle().set("opacity", "0.6").set("text-decoration", "line-through");
        }
        return line;
    }
}
