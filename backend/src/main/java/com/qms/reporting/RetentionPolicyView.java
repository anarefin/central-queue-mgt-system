package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** A retention policy as the API answers it (ticket 53, FR-SEC-032). */
record RetentionPolicyView(
        @JsonProperty("data_class") String dataClass,
        @JsonProperty("retention_months") int retentionMonths,
        String mode,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("updated_by") UUID updatedBy) {}
