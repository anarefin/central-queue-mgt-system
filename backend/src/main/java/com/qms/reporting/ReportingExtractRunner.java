package com.qms.reporting;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A nightly CSV extract of the reporting fact tables to a client-specified location (FR-INT-060, ticket 53), for
 * clients who run their own warehouse. Reads from the exact same {@code bi.ticket_fact_v1} view (V44) a client's
 * own BI tool would read directly (FR-RPT-023), so the two paths - live query and file drop - can never drift from
 * one another. CSV today; a Parquet writer needs a third-party dependency this ticket does not add, and
 * FR-INT-060 itself only asks for "Parquet or CSV", which CSV alone already satisfies.
 */
@Component
@Profile(Profiles.SERVING)
class ReportingExtractRunner {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String COLUMNS_SQL =
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'bi' AND table_name = 'ticket_fact_v1' ORDER BY ordinal_position";

    private final JdbcTemplate jdbc;
    private final ReportingExtractProperties properties;
    private final AuditWriter audit;
    private final Clock clock;

    ReportingExtractRunner(JdbcTemplate jdbc, ReportingExtractProperties properties, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    long tick() {
        Instant now = clock.instant();
        Path directory = Path.of(properties.location());
        Path file = directory.resolve("ticket_fact-" + FILE_DATE.format(now) + ".csv");
        long rowCount;
        try {
            Files.createDirectories(directory);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                rowCount = write(writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        audit.record(AuditEvent.of("reporting.extract_generated", "reporting_extract", null)
                .withAfter(Map.of("file", file.toString(), "row_count", rowCount)));
        return rowCount;
    }

    private long write(BufferedWriter writer) throws IOException {
        List<String> columns = jdbc.queryForList(COLUMNS_SQL, String.class);
        writer.write(String.join(",", columns));
        writer.write("\n");

        long[] rowCount = {0};
        IOException[] failure = {null};
        RowCallbackHandler handler = rs -> {
            if (failure[0] != null) return;
            try {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) line.append(',');
                    line.append(cell(text(rs.getObject(columns.get(i)))));
                }
                writer.write(line.toString());
                writer.write("\n");
                rowCount[0]++;
            } catch (IOException e) {
                failure[0] = e;
            }
        };
        jdbc.query("SELECT * FROM bi.ticket_fact_v1 ORDER BY issued_at", handler);
        if (failure[0] != null) throw failure[0];
        writer.flush();
        return rowCount[0];
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Timestamp ts) return ts.toInstant().toString();
        return value.toString();
    }

    /** A leading {@code = + - @ TAB CR} would be executed as a formula by spreadsheet software, so it is defused -
     * the same rule {@code com.qms.reporting.DetailedTokenReportCsvWriter} applies to its own export. */
    private static String cell(String value) {
        String safe = switch (value.isEmpty() ? ' ' : value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
