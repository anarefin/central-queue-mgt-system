package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Where the visitor waits: the zone's name, building and floor (FR-ISS-002). */
public record ZoneRef(
        UUID id,
        String name,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("floor_label") String floorLabel) {}
