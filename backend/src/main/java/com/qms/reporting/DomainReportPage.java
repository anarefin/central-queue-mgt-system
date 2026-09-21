package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /reports/{key}/run}'s answer for the {@code appointment}, {@code journey}, {@code feedback} and
 * {@code notification} keys (ticket 51, §16.1): one page of that key's own rows (a flexible {@code Map}, the same
 * shape {@link OperationalReportResponse} already uses for a row whose exact fields vary by key), plus {@code
 * extra} for whatever aggregate does not fit the row grain — the Appointment report's no-show rate per Service,
 * Agent and visitor category (FR-APT-043) and its own appointment-adherence rate (the §15.3 KPI ticket 50 left for
 * this ticket), or the Journey report's completion rate and per-stop wait (FR-QUE-064, the journey-completion KPI
 * ticket 50 also left for this ticket).
 */
record DomainReportPage(
        String key,
        @JsonProperty("generated_at") Instant generatedAt,
        int page,
        int size,
        @JsonProperty("total_rows") long totalRows,
        @JsonProperty("total_pages") long totalPages,
        List<Map<String, Object>> rows,
        Map<String, Object> extra) {}
