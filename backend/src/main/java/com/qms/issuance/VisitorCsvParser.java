package com.qms.issuance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal RFC 4180 reader: comma-separated fields, double-quote quoting with {@code ""} as an escaped quote inside
 * a quoted field, and CRLF or LF line endings. FR-INT-011 asks for exactly this format and nothing more exotic, so no
 * external CSV library is pulled in for it.
 */
final class VisitorCsvParser {

    /** The header, in file order, and every data row keyed by that header. */
    record ParsedCsv(List<String> header, List<Row> rows) {}

    /** One data row: {@code lineNumber} is 1-based and counts the header row, so the first data row is line 2. */
    record Row(int lineNumber, Map<String, String> cells) {}

    private VisitorCsvParser() {}

    static ParsedCsv parse(String content) {
        List<List<String>> records = splitRecords(content);
        if (records.isEmpty()) return new ParsedCsv(List.of(), List.of());
        List<String> header = records.get(0);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            if (fields.size() == 1 && fields.get(0).isEmpty()) continue; // a trailing blank line
            Map<String, String> cells = new LinkedHashMap<>();
            for (int col = 0; col < header.size(); col++) {
                cells.put(header.get(col), col < fields.size() ? fields.get(col) : "");
            }
            rows.add(new Row(i + 1, cells));
        }
        return new ParsedCsv(header, rows);
    }

    private static List<List<String>> splitRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int length = content.length();
        while (i < length) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    i++;
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    i++;
                }
                case '\r' -> i++;
                case '\n' -> {
                    fields.add(field.toString());
                    records.add(List.copyOf(fields));
                    fields.clear();
                    field.setLength(0);
                    i++;
                }
                default -> {
                    field.append(c);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(List.copyOf(fields));
        }
        return records;
    }
}
