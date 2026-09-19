package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** A waiting area within a site, labelled by building (optional) and floor (FR-CFG-003, ADR-0002). */
public record Zone(
        UUID id,
        @JsonProperty("site_id") UUID siteId,
        String name,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("floor_label") String floorLabel,
        @JsonProperty("display_order") int displayOrder,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
