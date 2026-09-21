package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** {@code GET /reports/jobs/{id}}'s answer (FR-RPT-004): a background export's progress, and once {@code done}, when
 * its download link expires. {@code row_count} is null until the job has actually counted the rows it wrote. */
record ReportExportJobView(
        UUID id,
        @JsonProperty("report_key") String reportKey,
        String format,
        String status,
        @JsonProperty("row_count") Long rowCount,
        @JsonProperty("requested_at") Instant requestedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("expires_at") Instant expiresAt,
        String error) {}
