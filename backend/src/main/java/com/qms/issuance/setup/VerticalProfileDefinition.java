package com.qms.issuance.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * What a vertical profile carries (SRS §3.3): label overrides for the terminology-remapping keys of §3.2, a starter
 * service catalogue, default priority classes, default numbering rules, default report selection and KPI
 * thresholds, and feature flags. Deserialised as-is from {@code profiles/<wire>.json}; nothing here is computed
 * from the profile id, so a sixth profile is one more data file, not one more branch (CFG-001).
 */
public record VerticalProfileDefinition(
        String id,
        /** Label key (e.g. {@code entity.visitor}) to language to value (§3.2). */
        Map<String, Map<String, String>> labels,
        @JsonProperty("starter_services") List<StarterService> starterServices,
        @JsonProperty("priority_classes") List<StarterPriorityClass> priorityClasses,
        @JsonProperty("numbering_defaults") NumberingDefaults numberingDefaults,
        @JsonProperty("report_defaults") List<String> reportDefaults,
        @JsonProperty("kpi_thresholds") Map<String, Integer> kpiThresholds,
        @JsonProperty("feature_flags") Map<String, Boolean> featureFlags) {

    public record StarterService(@JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("token_prefix") String tokenPrefix) {}

    public record StarterPriorityClass(@JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("headstart_minutes") int headstartMinutes) {}

    public record NumberingDefaults(
            @JsonProperty("sequence_start") int sequenceStart, int padding, @JsonProperty("reset_boundary") String resetBoundary) {}
}
