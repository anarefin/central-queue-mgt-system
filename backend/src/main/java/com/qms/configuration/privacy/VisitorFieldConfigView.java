package com.qms.configuration.privacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** One field's own config, as the API answers it. */
public record VisitorFieldConfigView(
        String surface,
        String field,
        boolean visible,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("updated_by") UUID updatedBy) {}
