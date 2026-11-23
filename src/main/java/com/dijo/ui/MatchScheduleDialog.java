package com.dijo.ui;

import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import java.time.Duration;
import java.util.Objects;

/**
 * Admin edit of where, when and by whom a match still to be played is officiated.
 * A blank court, time or official clears it.
 */
class MatchScheduleDialog extends Dialog {
    private final MatchService service;
    private final Match match;
    private final Runnable onChange;

    private final ComboBox<String> official = new ComboBox<>("Official");
    private final TextField court = new TextField("Court");
    private final TimePicker time = new TimePicker("Time");

    MatchScheduleDialog(MatchService service, Match match, Runnable onChange) {
        this.service = service;
        this.match = match;
        this.onChange = onChange;
        setWidth("min(420px, 100vw)");
        setHeaderTitle(match.roundName() + " · Match " + match.matchNo());

        official.setItems(service.officials());
        official.setValue(match.official());
        official.setPlaceholder("Not assigned");
        official.setClearButtonVisible(true);
        official.setWidthFull();
        court.setValue(match.court() == null ? "" : match.court());
        court.setPlaceholder("e.g. 2");
        court.setMaxLength(20);
        court.setWidthFull();
        time.setStep(Duration.ofMinutes(15));
        time.setValue(match.startTime());
        time.setClearButtonVisible(true);
        time.setWidthFull();

        VerticalLayout body = new VerticalLayout(PlayerUi.muted(match.eventName()),
                PlayerUi.muted(match.side1() + " vs " + match.side2()), court, time, official);
        body.setPadding(false);
        body.setSpacing(false);
        add(body);

        Button cancel = new Button("Cancel", e -> close());
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        Button save = new Button("Save", e -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(cancel, save);
    }

    private void save() {
        try {
            String id = match.eventId();
            service.schedule(id, match.round(), match.matchNo(), court.getValue(), time.getValue());
            if (!Objects.equals(official.getValue(), match.official())) {
                service.assignOfficial(id, match.round(), match.matchNo(), official.getValue());
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.ERROR);
            onChange.run();
            return;
        }
        Notification.show("Match updated", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.SUCCESS);
        onChange.run();
        close();
    }
}
