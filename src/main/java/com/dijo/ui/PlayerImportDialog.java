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
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import com.vaadin.flow.server.streams.UploadHandler;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bulk player import: upload the registration form CSV, check the preview, then import.
 * Only the name, payment amount, transaction reference and (optional) contact number
 * columns are used.
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
    private Details format;

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
                + "their name as the username and initial password. When a transaction reference is "
                + "given, the payment is recorded and the player is signed up, for the amount in the "
                + "file or, if that is blank, the entry fee. Rows already imported are skipped.");

        preview.addColumn(Row::line).setHeader("Line").setTextAlign(ColumnTextAlign.END).setFlexGrow(0);
        preview.addColumn(Row::name).setHeader("Player name").setFlexGrow(2);
        preview.addColumn(r -> r.phone() == null ? "—" : r.phone()).setHeader("Contact number");
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
        add(help, format(), upload, summary, preview, results);

        importButton.addThemeVariants(ButtonVariant.PRIMARY);
        importButton.setEnabled(false);
        importButton.addClickListener(e -> importRows());
        close.addThemeVariants(ButtonVariant.TERTIARY);
        getFooter().add(close, importButton);
    }

    /** The expected columns, an example and a sample file, open until a file is uploaded. */
    private Details format() {
        Span intro = PlayerUi.muted("The first row holds the column headings. Columns are found by their "
                + "heading, so they can be in any order, and other columns (timestamp, email, date of "
                + "birth, …) are ignored: the Google Forms export can be uploaded as it is. Comma- or "
                + "tab-separated, up to 2 MB.");
        Div columns = PlayerUi.rows(
                "Players Name(AITA/AADHAR)", "Required. The player's full name. Any heading with both "
                        + "\"player\" and \"name\" in it.",
                "Payment Amount", "Required column; a blank cell means the entry fee was paid. "
                        + "For example 500, ₹500 or Rs. 1,000.50. Any heading with \"amount\" in it.",
                "Transaction Reference", "Required column. A reference signs the player up; a blank cell "
                        + "creates the player without signing them up. For example the UPI reference. Any heading with \"transaction\" or "
                        + "\"reference\" in it.",
                "Contact Number", "Optional column, and a cell may be blank. 7-15 digits, with an "
                        + "optional leading +, spaces, - or brackets. Any heading with \"phone\", "
                        + "\"mobile\", \"WhatsApp\" or \"contact\" in it.");
        Pre example = new Pre(PlayerCsv.SAMPLE.stripTrailing());
        example.addClassName("csv-example");
        Anchor sample = new Anchor(DownloadHandler.fromInputStream(event -> {
            byte[] bytes = PlayerCsv.SAMPLE.getBytes(StandardCharsets.UTF_8);
            return new DownloadResponse(new ByteArrayInputStream(bytes), "players-sample.csv",
                    "text/csv", bytes.length);
        }), AttachmentType.DOWNLOAD, "");
        sample.add(VaadinIcon.DOWNLOAD.create(), new Span("Download sample CSV"));
        sample.addClassName("csv-sample-link");

        Details details = new Details("File format", new Div(intro, columns, example, sample));
        details.setOpened(true);
        details.setWidthFull();
        format = details;
        return details;
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
        format.setOpened(false);  // make room for the preview; it can be reopened
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
