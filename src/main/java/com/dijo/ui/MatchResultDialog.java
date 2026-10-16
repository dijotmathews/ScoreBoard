package com.dijo.ui;

import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.radiobutton.RadioGroupVariant;
import com.vaadin.flow.component.textfield.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Enter a match result. Officials save results for their matches; the admin can
 * also confirm (eliminating the loser and advancing the winner) or reopen one.
 * While open, the dialog redraws itself if someone else changes the match.
 */
class MatchResultDialog extends Dialog {
    private final MatchService service;
    private final EventUpdates updates;
    private final boolean admin;
    private final Runnable onChange;

    private final RadioButtonGroup<String> winner = new RadioButtonGroup<>("Winner");
    private final TextField score = new TextField("Score");
    private final VerticalLayout body = new VerticalLayout();
    private Match match;
    private Runnable unsubscribe;

    MatchResultDialog(MatchService service, EventUpdates updates, Match match, boolean admin,
            Runnable onChange) {
        this.service = service;
        this.updates = updates;
        this.admin = admin;
        this.onChange = onChange;
        setWidth("min(520px, 100vw)");
        addClassName("result-dialog");
        winner.addThemeVariants(RadioGroupVariant.AURA_HORIZONTAL);
        score.setPlaceholder("e.g. 21-15, 18-21, 21-19");
        score.setHelperText("Optional");
        score.setWidthFull();
        body.setPadding(false);
        add(body);
        render(match);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        unsubscribe = LiveReload.subscribe(attachEvent.getUI(), updates,
                eventId -> eventId.equals(match.eventId()), this::refresh);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
        super.onDetach(detachEvent);
    }

    /** Redraws with the latest state if this match changed elsewhere. */
    private void refresh() {
        if (!isOpened()) return;
        var latest = service.currentMatch(match.eventId(), match.round(), match.matchNo());
        if (latest.isEmpty()) {
            close();
            Notification.show("This match is no longer available", 4000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.WARNING);
            return;
        }
        if (latest.get().equals(match)) return;  // another match in the event changed
        render(latest.get());
        Notification.show("This match was just updated", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.WARNING);
    }

    private void render(Match match) {
        this.match = match;
        body.removeAll();
        getHeader().removeAll();
        getFooter().removeAll();

        setHeaderTitle(match.roundName() + " · Match " + match.matchNo());
        getHeader().add(statusBadge(match.status()));
        body.add(PlayerUi.muted(match.eventName()), matchup(match));

        boolean editable = !match.confirmed() && !match.byeMatch() && match.ready();
        if (match.noMatch()) {
            body.add(PlayerUi.muted("Both sides are BYEs, so this match is not played."));
        } else if (match.byeMatch() && match.winner() == null) {
            body.add(PlayerUi.muted("The player facing the BYE is not known yet; they will advance automatically."));
        } else if (match.byeMatch()) {
            body.add(PlayerUi.muted(match.winner() + " advances automatically; the BYE is eliminated."
                    + (match.confirmed() ? "" : " Confirm to move them on.")));
        } else if (!match.ready()) {
            body.add(PlayerUi.muted("The players for this match are not known yet."));
        } else if (editable) {
            winner.setItems(List.of(match.player1(), match.player2()));
            winner.setValue(match.winner());
            score.setValue(match.score() == null ? "" : match.score());
            body.add(winner, score);
        }
        String official = match.official() == null ? "Not assigned" : match.official();
        List<String> details = new ArrayList<>(List.of("Official", official));
        if (match.resultBy() != null) details.addAll(List.of("Result entered by", match.resultBy()));
        if (!editable && match.score() != null) details.addAll(List.of("Score", match.score()));
        body.add(PlayerUi.rows(details.toArray(String[]::new)));
        if (match.confirmed()) {
            body.add(PlayerUi.muted("Confirmed. " + loser() + " is eliminated."));
        } else if (match.winner() != null && !match.byeMatch()) {
            body.add(PlayerUi.muted("Waiting for the admin's confirmation."));
        }

        Button cancel = new Button("Close", e -> close());
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        getFooter().add(cancel);
        if (editable) {
            Button save = new Button("Save result", e -> save(false));
            if (!admin) save.addThemeVariants(ButtonVariant.PRIMARY);
            getFooter().add(save);
        }
        if (admin && !match.confirmed() && match.ready() && !match.noMatch()) {
            // Confirming an entered result keeps who entered it, unless the admin edits it.
            Button confirm = new Button(match.winner() == null ? "Save & confirm" : "Confirm", e -> {
                if (editable && changed()) save(true);
                else confirm();
            });
            confirm.addThemeVariants(ButtonVariant.PRIMARY);
            getFooter().add(confirm);
        }
        if (admin && match.confirmed()) {
            Button reopen = new Button("Reopen", e -> {
                if (run(() -> service.reopen(match.eventId(), match.round(), match.matchNo()))) {
                    done("Result reopened");
                }
            });
            reopen.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            reopen.getStyle().set("margin-inline-end", "auto");
            getFooter().addComponentAsFirst(reopen);
        }
    }

    /** Both sides with avatars; the winner, once known, gets a badge. */
    private static Div matchup(Match match) {
        Div matchup = new Div(side(match.side1(), match.player1() != null
                        && match.player1().equals(match.winner())),
                vs(), side(match.side2(), match.player2() != null && match.player2().equals(match.winner())));
        matchup.addClassName("matchup");
        return matchup;
    }

    private static Div side(String name, boolean won) {
        Avatar avatar = new Avatar("BYE".equals(name) || "TBD".equals(name) ? null : name);
        Span label = new Span(name);
        label.addClassName("matchup-name");
        Div side = new Div(avatar, label);
        if (won) side.add(PlayerUi.badge("Winner", BadgeVariant.SUCCESS));
        side.addClassName("matchup-side");
        if (won) side.addClassName("won");
        return side;
    }

    private static Span vs() {
        Span vs = new Span("vs");
        vs.addClassName("matchup-vs");
        return vs;
    }

    private static Badge statusBadge(Match.Status status) {
        return switch (status) {
            case WAITING_FOR_PLAYERS -> PlayerUi.badge("Waiting");
            case SCHEDULED -> PlayerUi.badge("To play");
            case AWAITING_CONFIRMATION -> PlayerUi.badge("Awaiting confirmation", BadgeVariant.WARNING);
            case CONFIRMED -> PlayerUi.badge("Confirmed", BadgeVariant.SUCCESS);
            case NO_MATCH -> PlayerUi.badge("No match");
        };
    }

    private void save(boolean thenConfirm) {
        if (!run(() -> service.recordResult(match.eventId(), match.round(), match.matchNo(),
                winner.getValue(), score.getValue()))) {
            return;
        }
        if (thenConfirm) confirm();
        else done(admin ? "Result saved" : "Result saved; awaiting the admin's confirmation");
    }

    private void confirm() {
        if (run(() -> service.confirmResult(match.eventId(), match.round(), match.matchNo()))) {
            done("Result confirmed");
        }
    }

    private boolean changed() {
        String entered = score.getValue().isBlank() ? null : score.getValue().strip();
        return !Objects.equals(winner.getValue(), match.winner())
                || !Objects.equals(entered, match.score());
    }

    private String loser() {
        if (match.byeMatch()) return "The BYE";
        return match.winner().equals(match.player1()) ? match.player2() : match.player1();
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
