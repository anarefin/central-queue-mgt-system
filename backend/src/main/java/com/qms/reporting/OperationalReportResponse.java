package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /reports/{key}/run}'s answer for every operational report key (ticket 50, §16.1, §15.2, §15.3):
 * {@code rows} is this report's own grain (one row per bucket/counter/agent/service/department/site) for the
 * requested period alone; {@code totals} is the same report's headline metrics for that whole period in one row,
 * {@code previous_totals} the identical computation over the previous equivalent period, and {@code change} the
 * absolute and percentage difference between them (FR-RPT-010, FR-MON-011) — every numeric field in {@code totals}
 * gets its own {@code change} entry, computed by {@link ChangeCalculator}. {@code extra} carries a report-specific
 * secondary breakdown that does not fit the row grain (today, only the service report's "average and P90 wait per
 * hour band", §15.3).
 */
record OperationalReportResponse(
        String key,
        @JsonProperty("generated_at") Instant generatedAt,
        Instant from,
        Instant to,
        @JsonProperty("previous_from") Instant previousFrom,
        @JsonProperty("previous_to") Instant previousTo,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        @JsonProperty("previous_totals") Map<String, Object> previousTotals,
        Map<String, Object> change,
        Map<String, Object> extra) {}
