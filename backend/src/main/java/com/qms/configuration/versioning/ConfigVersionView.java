package com.qms.configuration.versioning;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A {@link ConfigVersion} as the API shows it (FR-CFG-040): the author and moment of one past state, revertible by {@code id}. */
public record ConfigVersionView(
        UUID id, Map<String, Object> payload, @JsonProperty("changed_by") UUID changedBy, @JsonProperty("changed_at") Instant changedAt) {

    static ConfigVersionView of(ConfigVersion version) {
        return new ConfigVersionView(version.id(), version.payload(), version.changedBy(), version.changedAt());
    }
}
