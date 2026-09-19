package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Validated by {@link SiteRules}, so a failure names the wire field. */
record CreateZoneRequest(
        String name,
        @JsonProperty("floor_label") String floorLabel,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("display_order") Integer displayOrder) {}
