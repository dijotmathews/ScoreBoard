package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;

import com.dijo.benefits.PlayerCsv;
import com.dijo.benefits.PlayerCsv.Row;
import com.dijo.benefits.PlayerImportService;
import com.dijo.benefits.PlayerImportService.Outcome;
import com.dijo.benefits.PlayerImportService.Result;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bulk player import: upload the registration form CSV, check the preview, then import.
 * Only the name, payment amount and transaction reference columns are used.
 */
class PlayerImportDialog extends Dialog {
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final PlayerImportService service;
    private final Runnable onChange;
    private final Span summary = new Span();
    private final Grid<Row> preview = new Grid<>(Row.class, false);
    private final Grid<Result> results = new Grid<>(Result.class, false);
    private final Upload upload;
    private final Button importButton = new Button("Import");
    private final Button close = new Button("Cancel", e -> close());
    private List<Row> rows = List.of();

    PlayerImportDialog(PlayerImportService service, Runnable onChange) {
        this.service = service;
        this.onChange = onChange;
        setHeaderTitle("Import players from CSV");
        setWidth("min(900px, 100vw)");

        UI ui = UI.getCurrentOrThrow();
        upload = new Upload(UploadHandler.inMemory((metadata, data) -> ui.access(() -> showFile(data))));
        upload.setAcceptedFileTypes("text/csv", ".csv", "text/tab-separated-values", ".tsv", ".txt");
        upload.setMaxFiles(1);
        upload.setMaxFileSize(MAX_FILE_BYTES);
        upload.setWidthFull();

        Span help = PlayerUi.muted("Upload the registration form export. Each player is created with "
                + "their name as the username and initial password. When a payment amount and "
                + "transaction reference are given, that payment is recorded as is and the player "
                + "is signed up. Rows already imported are skipped.");

        preview.addColumn(Row::line).setHeader("Line").setTextAlign(ColumnTextAlign.END).setFlexGrow(0);
        preview.addColumn(Row::name).setHeader("Player name").setFlexGrow(2);
        preview.addColumn(r -> r.amountPaise() == null ? "—" : PlayerEditorDialog.rupees(r.amountPaise()))
                .setHeader("Amount").setTextAlign(ColumnTextAlign.END);
        preview.addColumn(r -> r.reference() == null ? "—" : r.reference())
                .setHeader("Transaction reference").setFlexGrow(2);
        preview.addComponentColumn(r -> r.valid() ? badge("Ready", BadgeVariant.SUCCESS)
                : badge(r.problem(), BadgeVariant.ERROR)).setHeader("Check");
        preview.getColumns().forEach(c -> c.setAutoWidth(true));

        results.addColumn(r -> r.row().line()).setHeader("Line").setTextAlign(ColumnTextAlign.END)
                .setFlexGrow(0);
        results.addColumn(r -> r.row().name()).setHeader("Player name").setFlexGrow(2);
        results.addColumn(r -> r.username() == null ? "—" : r.username()).setHeader("Username");
        results.addComponentColumn(PlayerImportDialog::outcomeBadge).setHeader("Result");
        results.addColumn(r -> r.message() == null ? "" : r.message()).setHeader("Details").setFlexGrow(3);
        results.getColumns().forEach(c -> c.setAutoWidth(true));

        for (Grid<?> g : List.of(preview, results)) {
            g.setHeight("320px");
            g.setVisible(false);
        }
        summary.setVisible(false);
        add(help, upload, summary, preview, results);

        importButton.addThemeVariants(ButtonVariant.PRIMARY);
        importButton.setEnabled(false);
        importButton.addClickListener(e -> importRows());
        close.addThemeVariants(ButtonVariant.TERTIARY);
        getFooter().add(close, importButton);
    }

    /** Parses an uploaded file into the preview. */
    void showFile(byte[] data) {
        try {
            rows = PlayerCsv.parse(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            rows = List.of();
            Notification.show(e.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.ERROR);
        }
        long ready = rows.stream().filter(Row::valid).count();
        summary.setText(rows.isEmpty() ? "No players found in the file."
                : ready + " of " + rows.size() + " rows ready to import"
                + (ready < rows.size() ? "; rows with problems will be skipped." : "."));
        summary.setVisible(true);
        results.setVisible(false);
        preview.setItems(rows);
        preview.setVisible(!rows.isEmpty());
        importButton.setText("Import " + ready + (ready == 1 ? " player" : " players"));
        importButton.setEnabled(ready > 0);
    }

    private void importRows() {
        List<Result> outcome = service.importPlayers(rows);
        Map<Outcome, Long> counts = outcome.stream()
                .collect(Collectors.groupingBy(Result::outcome, Collectors.counting()));
        long signedUp = counts.getOrDefault(Outcome.SIGNED_UP, 0L);
        long created = counts.getOrDefault(Outcome.CREATED, 0L);
        long skipped = counts.getOrDefault(Outcome.SKIPPED, 0L);
        long failed = counts.getOrDefault(Outcome.FAILED, 0L);
        summary.setText((signedUp + created) + " players created (" + signedUp + " signed up, "
                + created + " without payment), " + skipped + " skipped, " + failed + " failed.");
        preview.setVisible(false);
        results.setItems(outcome);
        results.setVisible(true);
        upload.setVisible(false);
        importButton.setVisible(false);
        close.setText("Close");
        close.removeThemeVariants(ButtonVariant.TERTIARY);
        close.addThemeVariants(ButtonVariant.PRIMARY);
        rows = List.of();
        onChange.run();
    }

    private static Badge outcomeBadge(Result result) {
        return switch (result.outcome()) {
            case SIGNED_UP -> badge("Signed up", BadgeVariant.SUCCESS);
            case CREATED -> badge("Created, not paid", BadgeVariant.WARNING);
            case SKIPPED -> badge("Skipped", BadgeVariant.CONTRAST);
            case FAILED -> badge("Failed", BadgeVariant.ERROR);
        };
    }
}
