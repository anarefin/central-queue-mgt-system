package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** A serving position inside a zone (FR-CFG-004). {@code siteId} is derived from the zone. */
public record Counter(
        UUID id,
        @JsonProperty("zone_id") UUID zoneId,
        @JsonProperty("site_id") UUID siteId,
        String label,
        @JsonProperty("location_note") String locationNote,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
