package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Absent (null) fields are left unchanged; {@code name_i18n}, when given, replaces the whole set of names. Same for
 * {@code custom_level_options}: given, it replaces the whole option list; the level is disabled by sending an empty
 * list. {@code team_selectable} and {@code individual_selectable} always replace, since {@code false} is a value a
 * client must be able to send (ticket 26, FR-ISS-011).
 */
record UpdateServiceGroupRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("display_order") Integer displayOrder,
        @JsonProperty("team_selectable") Boolean teamSelectable,
        @JsonProperty("individual_selectable") Boolean individualSelectable,
        @JsonProperty("custom_level_name_i18n") Map<String, String> customLevelNameI18n,
        @JsonProperty("custom_level_options") List<ServiceGroup.CustomLevelOption> customLevelOptions) {}
