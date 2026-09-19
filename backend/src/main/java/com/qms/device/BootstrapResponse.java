package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code GET /config/bootstrap} (SRS §20.4): everything a device needs to render without another round trip.
 * The SRS names the four parts in prose only ("branding, languages, layout and service tree") and gives no
 * field-level schema, so the shape below is this build's choice.
 */
record BootstrapResponse(Branding branding, List<String> languages, Layout layout, @JsonProperty("service_tree") List<ServiceTreeGroup> serviceTree) {

    record Branding(@JsonProperty("site_name") String siteName, @JsonProperty("default_language") String defaultLanguage) {}

    /** Present only for a display, which is scoped to one zone; a kiosk's layout is site-level only. */
    record Layout(@JsonProperty("zone") ZoneLayout zone) {}

    record ZoneLayout(
            UUID id,
            String name,
            @JsonProperty("building_label") String buildingLabel,
            @JsonProperty("floor_label") String floorLabel,
            List<CounterLayout> counters) {}

    record CounterLayout(UUID id, String label) {}

    record ServiceTreeGroup(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, List<ServiceTreeEntry> services) {}

    record ServiceTreeEntry(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}
}
