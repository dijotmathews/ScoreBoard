package com.dijo.benefits;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Reads the registration form export (e.g. a Google Forms CSV) for a bulk player import.
 * Only the player's name, payment amount and transaction reference are used; other
 * columns (timestamp, email, date of birth, birth certificate, events) are ignored.
 * Columns are found by their header, so their order does not matter. Comma- and
 * tab-separated files are both accepted.
 */
public final class PlayerCsv {
    /** One data row. amountPaise and reference are null when blank; problem is null when valid. */
    public record Row(int line, String name, Long amountPaise, String reference, String problem) {
        public boolean valid() { return problem == null; }
    }

    private PlayerCsv() {}

    public static List<Row> parse(String text) {
        if (text.startsWith("﻿")) text = text.substring(1); // Excel's byte order mark
        List<List<String>> records = records(text);
        if (records.isEmpty()) throw new IllegalArgumentException("The file is empty");

        List<String> header = records.getFirst().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        // "Players Name(AITA/AADHAR)": the player's name as on their AITA or Aadhaar card.
        int name = column(header, h -> h.contains("player") && h.contains("name"),
                "Players Name(AITA/AADHAR)");
        int amount = column(header, h -> h.contains("amount"), "Payment Amount");
        int reference = column(header, h -> h.contains("transaction") || h.contains("reference"),
                "Transaction Reference");

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i);
            if (r.stream().allMatch(String::isBlank)) continue;
            rows.add(row(i + 1, cell(r, name), cell(r, amount), cell(r, reference)));
        }
        return rows;
    }

    private static Row row(int line, String name, String amount, String reference) {
        String ref = reference.isEmpty() ? null : reference;
        if (name.isEmpty()) return new Row(line, name, null, ref, "Player name is missing");
        if (amount.isEmpty()) return new Row(line, name, null, ref, null);
        try {
            return new Row(line, name, paise(amount), ref, null);
        } catch (IllegalArgumentException e) {
            return new Row(line, name, null, ref, "Payment amount \"" + amount + "\" is not a number");
        }
    }

    /** "500", "₹500", "Rs. 1,000.50" → paise. */
    static long paise(String amount) {
        String digits = amount.replaceAll("(?i)rs\\.?|inr|[₹,\\s]", "");
        BigDecimal rupees = new BigDecimal(digits); // NumberFormatException is an IllegalArgumentException
        if (rupees.signum() <= 0 || rupees.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(amount);
        }
        return rupees.movePointRight(2).longValueExact();
    }

    private static int column(List<String> header, Predicate<String> matches, String label) {
        for (int i = 0; i < header.size(); i++) {
            if (matches.test(header.get(i))) return i;
        }
        throw new IllegalArgumentException("The file has no \"" + label + "\" column");
    }

    private static String cell(List<String> record, int index) {
        return index < record.size() ? record.get(index).strip() : "";
    }

    /** RFC 4180 records: quoted fields may hold separators, quotes ("") and line breaks. */
    private static List<List<String>> records(String text) {
        int firstLineEnd = text.indexOf('\n');
        String firstLine = firstLineEnd < 0 ? text : text.substring(0, firstLineEnd);
        char separator = firstLine.indexOf('\t') >= 0 ? '\t' : ',';

        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
