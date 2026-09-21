package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /reports/peak-hours/run}'s answer (FR-RPT-011): ticket volume by hour-of-day (0-23) and ISO
 * day-of-week (1 Monday .. 7 Sunday, the same convention {@code RosteredHours}/{@code business_hours.weekday}
 * already use) — one cell per combination that had at least one ticket, so staffing can be planned against where
 * the load actually falls.
 */
record PeakHoursResponse(String key, @JsonProperty("generated_at") Instant generatedAt, Instant from, Instant to, List<Map<String, Object>> cells) {}
