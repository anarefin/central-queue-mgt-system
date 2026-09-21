package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** One attempted delivery, most recent first ({@code GET /reports/schedules/{id}/deliveries}, ticket 52): a
 * schedule's own delivery log — "failures visible in the delivery log". {@code recipient} is {@code null} when the
 * report itself failed to generate, before any recipient was attempted. */
record ReportScheduleDeliveryView(
        UUID id,
        @JsonProperty("run_at") Instant runAt,
        String recipient,
        String status,
        @JsonProperty("row_count") Long rowCount,
        String error,
        @JsonProperty("attempted_at") Instant attemptedAt) {}
