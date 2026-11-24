package com.dijo.benefits;

import com.dijo.benefits.PlayerCsv.Row;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Bulk player import from the registration form CSV. Each row is imported on its own,
 * so one bad row does not stop the rest. A row creates the player and, when it has a
 * transaction reference, records their entry fee payment, signing them up. The amount
 * is the row's, or the entry fee when the row has none.
 * Rows already imported, by transaction reference or by player name, are skipped, so
 * uploading the same file again is safe.
 */
@Service
public class PlayerImportService {
    public enum Outcome { SIGNED_UP, CREATED, SKIPPED, FAILED }

    /** username is set when the player was created. */
    public record Result(Row row, Outcome outcome, String username, String message) {}

    private final JdbcTemplate jdbc;
    private final PlayerAccountService accounts;
    private final PlayerBenefitsService benefits;

    public PlayerImportService(JdbcTemplate jdbc, PlayerAccountService accounts,
            PlayerBenefitsService benefits) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.benefits = benefits;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<Result> importPlayers(List<Row> rows) {
        List<Result> results = new ArrayList<>();
        for (Row row : rows) results.add(importRow(row));
        return results;
    }

    private Result importRow(Row row) {
        if (!row.valid()) return new Result(row, Outcome.FAILED, null, row.problem());
        if (row.reference() != null && referenceRecorded(row.reference())) {
            return new Result(row, Outcome.SKIPPED, null, "Transaction reference already recorded");
        }
        if (nameTaken(row.name())) {
            return new Result(row, Outcome.SKIPPED, null, "A player with this name already exists");
        }

        // A mistyped number should not stop the player being imported; it is left out instead.
        String phone = null;
        String phoneNote = null;
        try {
            phone = PlayerAccountService.normalizePhone(row.phone());
        } catch (IllegalArgumentException e) {
            phoneNote = "Contact number \"" + row.phone() + "\" not saved: " + e.getMessage();
        }

        String username;
        try {
            username = accounts.createPlayer(row.name(), phone, true);
        } catch (IllegalArgumentException e) {
            return new Result(row, Outcome.FAILED, null, e.getMessage());
        }

        if (row.reference() == null) {
            return new Result(row, Outcome.CREATED, username,
                    join("No transaction reference; payment not recorded", phoneNote));
        }
        var settings = benefits.settings();
        if (settings.isEmpty()) {
            return new Result(row, Outcome.CREATED, username,
                    join("Entry fee not set in Benefit settings; payment not recorded", phoneNote));
        }
        // A transaction reference signs the player up; without an amount, the entry fee is assumed.
        long amount = row.amountPaise() != null ? row.amountPaise() : settings.get().feePaise();
        try {
            benefits.recordImportedPayment(username, row.reference(), amount);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Result(row, Outcome.CREATED, username,
                    join("Payment not recorded: " + e.getMessage(), phoneNote));
        }
        String feeNote = row.amountPaise() != null ? null
                : "No payment amount; recorded the entry fee of " + rupees(amount);
        return new Result(row, Outcome.SIGNED_UP, username,
                feeNote == null ? phoneNote : join(feeNote, phoneNote));
    }

    private static String rupees(long paise) {
        return "₹" + java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }

    private static String join(String message, String note) {
        return note == null ? message : message + ". " + note;
    }

    private boolean referenceRecorded(String reference) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM entry_payments WHERE payment_reference = ?",
                Integer.class, reference);
        return count != null && count > 0;
    }

    /** Compared as the account service stores names: trimmed, with runs of spaces collapsed. */
    private boolean nameTaken(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE full_name = ? COLLATE NOCASE",
                Integer.class, name.strip().replaceAll("\\s+", " "));
        return count != null && count > 0;
    }
}
