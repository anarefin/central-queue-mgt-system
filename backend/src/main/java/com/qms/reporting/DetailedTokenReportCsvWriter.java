package com.qms.reporting;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * CSV export (FR-RPT-003): FR-RPT-006's header block as its own {@code label,value} lines, a blank line, then the
 * column header row and one row per Ticket — raw values throughout, never a formatted display string. Writes a row
 * at a time straight to {@code out} (true streaming: a 1,000,000-row export, NFR-PERF-006, never holds more than one
 * row in memory), the same {@code AuditCsv} escaping (a leading {@code = + - @} or control character is defused so
 * spreadsheet software never executes an exported cell as a formula).
 */
@Component
class DetailedTokenReportCsvWriter {

    long write(OutputStream out, List<String[]> headerLines, List<String> columnHeaders, ReportRowSource source, String language) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        for (String[] line : headerLines) {
            writer.write(cell(line[0]) + "," + cell(line[1]));
            writer.write("\n");
        }
        writer.write("\n");
        writer.write(columnHeaders.stream().map(DetailedTokenReportCsvWriter::cell).collect(Collectors.joining(",")));
        writer.write("\n");

        AtomicLong count = new AtomicLong();
        IOException[] failure = new IOException[1];
        source.forEach(row -> {
            if (failure[0] != null) return;
            try {
                writer.write(DetailedTokenReportColumns.cells(row, language).stream().map(v -> cell(String.valueOf(v))).collect(Collectors.joining(",")));
                writer.write("\n");
                count.incrementAndGet();
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        writer.flush();
        return count.get();
    }

    /** A leading {@code = + - @ TAB CR} would be executed as a formula by spreadsheet software, so it is defused —
     * the same rule {@code com.qms.audit.AuditCsv} applies to its own export. */
    private static String cell(String value) {
        if (value == null) return "";
        String safe = switch (value.isEmpty() ? ' ' : value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
