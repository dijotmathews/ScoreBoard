package com.dijo.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;

/** Small building blocks shared by the app's pages, styled in styles.css. */
final class PlayerUi {
    private PlayerUi() {}

    /** Avatar, a heading text and the player's sign-up status. */
    static Div header(String username, String text, boolean signedUp) {
        return header(username, text, signedUp
                ? badge("Signed up", BadgeVariant.SUCCESS) : badge("Not signed up", BadgeVariant.WARNING));
    }

    /** Avatar, a heading text and a status badge. */
    static Div header(String username, String text, Badge status) {
        Span heading = new Span(text);
        heading.addClassName("profile-name");
        Div header = new Div(new Avatar(username), heading, status);
        header.addClassName("profile-header");
        return header;
    }

    static Card card(String title, Badge status) {
        Card card = new Card();
        card.setWidthFull();
        card.setTitle(title);
        if (status != null) card.setHeaderSuffix(status);
        return card;
    }

    static Badge badge(String text, BadgeVariant... variants) {
        Badge badge = new Badge(text);
        badge.addThemeVariants(variants);
        return badge;
    }

    /** Label/value pairs laid out as two aligned columns. */
    static Div rows(String... labelsAndValues) {
        Div rows = new Div();
        rows.addClassName("detail-rows");
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            Span label = new Span(labelsAndValues[i]);
            label.addClassName("detail-label");
            rows.add(label, new Span(labelsAndValues[i + 1]));
        }
        return rows;
    }

    /** A list row: main text with an optional secondary line, and something on the right. */
    static Div listRow(String main, Span sub, Component end) {
        Div text = new Div(new Span(main));
        text.addClassName("list-row-text");
        if (sub != null) {
            sub.addClassName("list-row-sub");
            text.add(sub);
        }
        Div row = new Div(text, end);
        row.addClassName("list-row");
        return row;
    }

    static Div list() {
        Div list = new Div();
        list.addClassName("list");
        return list;
    }

    /** Page title with a one-line description, and actions on the right. */
    static Div pageHeader(String title, String description, Component... actions) {
        Div titles = new Div(new H1(title), muted(description));
        titles.addClassName("page-titles");
        Div right = new Div(actions);
        right.addClassName("page-actions");
        Div header = new Div(titles, right);
        header.addClassName("page-header");
        return header;
    }

    /** A summary figure: label, value and an optional muted detail line. */
    static Div statTile(String label, String value, String detail) {
        Span labelText = new Span(label);
        labelText.addClassName("stat-label");
        Span valueText = new Span(value);
        valueText.addClassName("stat-value");
        Div tile = new Div(labelText, valueText);
        if (detail != null) tile.add(muted(detail));
        tile.addClassName("stat-tile");
        return tile;
    }

    static Span muted(String text) {
        Span span = new Span(text);
        span.addClassName("muted");
        return span;
    }
}
