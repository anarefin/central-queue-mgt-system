package com.qms.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One threshold breach streak (SRS §15.4, FR-MON-021): open until acknowledged (FR-MON-022); repeated breaches of
 * the same key while open bump {@code breachCount} and {@code lastBreachedAt} instead of a new row (FR-MON-023).
 */
public record Alert(
        UUID id,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("threshold_type") String thresholdType,
        @JsonProperty("subject_id") UUID subjectId,
        String state,
        @JsonProperty("breach_count") int breachCount,
        @JsonProperty("measured_value") BigDecimal measuredValue,
        @JsonProperty("threshold_value") BigDecimal thresholdValue,
        @JsonProperty("first_breached_at") Instant firstBreachedAt,
        @JsonProperty("last_breached_at") Instant lastBreachedAt,
        @JsonProperty("escalated_at") Instant escalatedAt,
        @JsonProperty("acknowledged_at") Instant acknowledgedAt,
        @JsonProperty("acknowledged_by") UUID acknowledgedBy,
        @JsonProperty("acknowledgement_note") String acknowledgementNote,
        @JsonProperty("created_at") Instant createdAt) {

    static final String OPEN = "open";
    static final String ACKNOWLEDGED = "acknowledged";
}
