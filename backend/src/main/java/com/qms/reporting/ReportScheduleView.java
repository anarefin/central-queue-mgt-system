package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A schedule as the API answers it (ticket 52, FR-RPT-005). */
record ReportScheduleView(
        UUID id,
        @JsonProperty("report_key") String reportKey,
        String cadence,
        String format,
        List<String> recipients,
        ReportScheduleFilter filter,
        boolean enabled,
        @JsonProperty("created_by") UUID createdBy,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("next_run_at") Instant nextRunAt,
        @JsonProperty("last_run_at") Instant lastRunAt) {}
