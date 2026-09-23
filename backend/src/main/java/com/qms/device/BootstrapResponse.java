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
record BootstrapResponse(
        Branding branding,
        List<String> languages,
        Layout layout,
        @JsonProperty("service_tree") List<ServiceTreeGroup> serviceTree,
        @JsonProperty("print_template") PrintTemplate printTemplate,
        /** Every feature flag's org-wide master switch, by wire key (ticket 68): a device reads its own copy here
         * rather than calling {@code GET /setup/feature-flags} itself, the same "shipped with bootstrap so a device
         * never needs a second round trip" reasoning {@code printTemplate} already follows. */
        @JsonProperty("feature_flags") Map<String, Boolean> featureFlags,
        /** Every terminology override for the Site's default language (SRS §3.2, ticket 69), key to value: a kiosk
         * or display refreshes this the same way it already refreshes {@code featureFlags}, by refetching bootstrap
         * whole on {@code config.changed} rather than a second endpoint. */
        Map<String, String> labels) {

    /**
     * {@code siteName}/{@code defaultLanguage} are per-site; {@code orgName}, {@code primaryColor} and {@code logoUrl}
     * are the organisation-wide branding of ticket 27 (FR-CFG-030), included here so a device never needs a second
     * round trip to render it.
     */
    record Branding(
            @JsonProperty("site_name") String siteName,
            @JsonProperty("default_language") String defaultLanguage,
            @JsonProperty("org_name") String orgName,
            @JsonProperty("primary_color") String primaryColor,
            @JsonProperty("logo_url") String logoUrl) {}

    /** The printed token layout (ticket 27, FR-CFG-031), shipped with bootstrap so a kiosk prints it without another call. */
    record PrintTemplate(List<String> fields, @JsonProperty("notice_line") String noticeLine) {}

    /** Present only for a display, which is scoped to one zone; a kiosk's layout is site-level only. */
    record Layout(@JsonProperty("zone") ZoneLayout zone) {}

    record ZoneLayout(
            UUID id,
            String name,
            @JsonProperty("building_label") String buildingLabel,
            @JsonProperty("floor_label") String floorLabel,
            List<CounterLayout> counters) {}

    record CounterLayout(UUID id, String label) {}

    /**
     * A group's kiosk selection tree (ticket 26, FR-ISS-010, FR-ISS-011), shipped with the tree itself so the kiosk
     * never needs another round trip to know which of the optional levels to walk the visitor through. {@code
     * teamSelectable} and {@code individualSelectable} gate the individual-agent level together (see {@link
     * com.qms.configuration.catalogue.ServiceGroup}'s header); {@code customLevel} is null when the group has none.
     */
    record ServiceTreeGroup(
            UUID id,
            @JsonProperty("name_i18n") Map<String, String> nameI18n,
            List<ServiceTreeEntry> services,
            @JsonProperty("team_selectable") boolean teamSelectable,
            @JsonProperty("individual_selectable") boolean individualSelectable,
            @JsonProperty("custom_level") CustomLevel customLevel) {}

    /** {@code visitorIdentifier} is {@code not_required}, {@code optional} or {@code mandatory} (FR-CFG-013), read at the kiosk without another call. */
    record ServiceTreeEntry(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("visitor_identifier") String visitorIdentifier) {}

    record CustomLevel(@JsonProperty("name_i18n") Map<String, String> nameI18n, List<CustomLevelOption> options) {}

    record CustomLevelOption(String id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}
}
