package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** The body of {@code POST /reports/schedules} and {@code PUT /reports/schedules/{id}} (ticket 52, FR-RPT-005):
 * which report, how often, in what format, to whom, and over what scope. {@code report_key} is immutable once
 * created (an update only ever changes cadence, format, recipients, filter or enabled); {@code enabled} is ignored
 * on create (a new schedule always starts enabled) and defaults to the schedule's current value on update when
 * left out. */
record ReportScheduleRequest(
        @JsonProperty("report_key") String reportKey,
        String cadence,
        String format,
        List<String> recipients,
        ReportScheduleFilter filter,
        Boolean enabled) {}
