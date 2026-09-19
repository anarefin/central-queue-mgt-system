package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Absent (null) fields are left unchanged; an empty {@code building_label} clears it. Validated by {@link SiteRules}. */
record UpdateZoneRequest(
        String name,
        @JsonProperty("floor_label") String floorLabel,
        @JsonProperty("building_label") String buildingLabel,
        @JsonProperty("display_order") Integer displayOrder) {}
