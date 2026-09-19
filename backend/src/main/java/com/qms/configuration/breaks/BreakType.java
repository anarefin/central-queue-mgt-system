package com.qms.configuration.breaks;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A kind of break an Agent may take, such as lunch or prayer, with an optional maximum duration (FR-AGT-020). Break types
 * are organisation-wide and are deactivated, never deleted, so a break already taken keeps resolving its type.
 */
public record BreakType(
        UUID id,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("max_minutes") Integer maxMinutes,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
