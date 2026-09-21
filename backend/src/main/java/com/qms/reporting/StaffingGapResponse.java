package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /reports/staffing-gap/run}'s answer (FR-RPT-012): one row per hour band (0-23) across the requested
 * period, each with tickets offered, counter-hours available and SLA attainment — the three figures FR-RPT-012
 * names side by side so a gap between offered volume and staffed capacity is visible at a glance.
 */
record StaffingGapResponse(String key, @JsonProperty("generated_at") Instant generatedAt, Instant from, Instant to, List<Map<String, Object>> rows) {}
