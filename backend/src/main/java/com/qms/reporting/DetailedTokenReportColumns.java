package com.qms.reporting;

import com.qms.platform.i18n.Messages;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The detailed token report's §16.1 column set, shared by every export writer (ticket 49) and matching the on-screen
 * table's own order ({@code F/apps/admin/src/components/DetailedTokenReportCard.tsx}'s {@code COLUMNS}). Header
 * labels reuse the {@code reports.detailedToken.col.*} keys ticket 48 already put in both language packs; a data
 * cell is always the row's raw value (FR-RPT-003) — an ISO-8601 instant, not a locale-formatted date/time, and a
 * localised name resolved once to the caller's own language rather than left as the {name: text} map the on-screen
 * table renders from. FR-SEC-022 (free-text notes excluded from a default export) needs no filtering here: {@code
 * reporting.ticket_fact} carries no free-text field at all — the detailed token report has none to exclude.
 */
final class DetailedTokenReportColumns {

    private DetailedTokenReportColumns() {}

    /** {@code messages.text("reports.detailedToken.col." + key, language)} for each column, in display order. */
    static final List<String> KEYS = List.of(
            "token", "visitorCode", "visitorName", "category", "serviceGroup", "service", "channel", "priority", "issueTime", "callTime", "startTime",
            "endTime", "wait", "serviceDuration", "counter", "agent", "outcome", "transfers");

    static List<String> headers(Messages messages, String language) {
        return KEYS.stream().map(key -> messages.text("reports.detailedToken.col." + key, language)).toList();
    }

    /** One row's cells, in the same order as {@link #headers}: raw values only (FR-RPT-003). */
    static List<Object> cells(DetailedTokenReportReads.Row row, String language) {
        return List.of(
                row.tokenNumber(),
                nullToEmpty(row.visitorCode()),
                nullToEmpty(row.visitorName()),
                nullToEmpty(row.visitorCategory()),
                name(row.serviceGroupName(), language),
                name(row.serviceName(), language),
                row.channel(),
                name(row.priorityClassName(), language),
                isoOrEmpty(row.issuedAt()),
                isoOrEmpty(row.calledAt()),
                isoOrEmpty(row.servedAt()),
                isoOrEmpty(row.closedAt()),
                row.waitSeconds() == null ? "" : row.waitSeconds(),
                row.serviceSeconds() == null ? "" : row.serviceSeconds(),
                nullToEmpty(row.counterLabel()),
                nullToEmpty(row.agentName()),
                name(row.outcomeLabel(), language),
                row.transfers());
    }

    private static String name(Map<String, String> names, String language) {
        if (names == null || names.isEmpty()) return "";
        String direct = names.get(language);
        return direct != null ? direct : names.values().stream().findFirst().orElse("");
    }

    private static String isoOrEmpty(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
