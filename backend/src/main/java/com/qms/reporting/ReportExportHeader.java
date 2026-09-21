package com.qms.reporting;

import com.qms.platform.i18n.Messages;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * FR-RPT-006's header block: report name, filters applied, generation timestamp with timezone, and the generating
 * user — one {@code [label, value]} pair per line, in the order every {@code DetailedTokenReportExportWriter}
 * writes them above the data table. Labels are localised (SRS §27.5's "strings in both packs": {@code
 * reports.export.header.*} in {@code messages_en.properties}/{@code messages_bn.properties}); values are raw
 * (report key, ISO instants, ids), never a formatted display string, matching FR-RPT-003's own rule for the data
 * rows below them.
 */
final class ReportExportHeader {

    private ReportExportHeader() {}

    static List<String[]> lines(Messages messages, String language, String reportKey, DetailedTokenReportFilter filter, Instant generatedAt, String generatedBy) {
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {messages.text("reports.export.header.report", language), reportKey});
        lines.add(new String[] {messages.text("reports.export.header.filters", language), filterSummary(filter)});
        lines.add(new String[] {
            messages.text("reports.export.header.generatedAt", language), DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(generatedAt.atOffset(ZoneOffset.UTC))
        });
        lines.add(new String[] {messages.text("reports.export.header.generatedBy", language), generatedBy});
        return lines;
    }

    private static String filterSummary(DetailedTokenReportFilter filter) {
        StringJoiner joiner = new StringJoiner("; ");
        add(joiner, "from", filter.from());
        add(joiner, "to", filter.to());
        add(joiner, "site_id", filter.siteId());
        add(joiner, "zone_id", filter.zoneId());
        add(joiner, "service_group_id", filter.serviceGroupId());
        add(joiner, "service_id", filter.serviceId());
        add(joiner, "agent_id", filter.agentId());
        add(joiner, "priority_class_id", filter.priorityClassId());
        add(joiner, "channel", filter.channel());
        add(joiner, "visitor_category", filter.visitorCategory());
        return joiner.length() == 0 ? "-" : joiner.toString();
    }

    private static void add(StringJoiner joiner, String field, Object value) {
        if (value != null) joiner.add(field + "=" + value);
    }
}
