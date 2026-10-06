package com.dijo.ui;

import com.dijo.events.EventUpdates;
import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
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
    private final TextField score = new TextField("Score (optional)");
    private final VerticalLayout body = new VerticalLayout();
    private Match match;
    private Runnable unsubscribe;

    MatchResultDialog(MatchService service, EventUpdates updates, Match match, boolean admin,
            Runnable onChange) {
        this.service = service;
        this.updates = updates;
        this.admin = admin;
        this.onChange = onChange;
        setWidth("min(480px, 100vw)");
        score.setPlaceholder("e.g. 21-15, 18-21, 21-19");
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
        getFooter().removeAll();

        setHeaderTitle(match.eventName() + " • " + match.roundName() + ", match " + match.matchNo());
        body.add(new Span(match.side1() + " vs " + match.side2()));

        boolean editable = !match.confirmed() && !match.byeMatch() && match.ready();
        if (match.byeMatch()) {
            body.add(new Span(match.winner() + " advances; the BYE is eliminated."));
        } else if (!match.ready()) {
            body.add(new Span("Both players are not known yet."));
        } else {
            winner.setItems(List.of(match.player1(), match.player2()));
            winner.setValue(match.winner());
            winner.setReadOnly(!editable);
            score.setValue(match.score() == null ? "" : match.score());
            score.setReadOnly(!editable);
            body.add(winner, score);
        }
        if (match.confirmed()) {
            body.add(new Span("Confirmed. " + loser() + " is eliminated."));
        } else if (match.resultBy() != null) {
            body.add(new Span("Result entered by " + match.resultBy() + "; awaiting the admin's confirmation."));
        }

        Button cancel = new Button("Close", e -> close());
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        getFooter().add(cancel);
        if (editable) {
            Button save = new Button("Save result", e -> save(false));
            if (!admin) save.addThemeVariants(ButtonVariant.PRIMARY);
            getFooter().add(save);
        }
        if (admin && !match.confirmed() && match.ready()) {
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
