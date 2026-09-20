package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Where the visitor waits: the zone's name, building and floor (FR-ISS-002), and its optional wayfinding image (FR-MOB-032, ticket 37). */
public record ZoneRef(
        UUID id,
        String name,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("floor_label") String floorLabel,
        @JsonProperty("wayfinding_image_url") String wayfindingImageUrl) {}
