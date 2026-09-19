package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Validated by {@link CatalogueRules}, so a failure names the wire field. The kiosk selection-tree fields (ticket 26,
 * FR-ISS-010, FR-ISS-011) default to disabled when left out, same as {@link ServiceGroup}'s defaults.
 */
record CreateServiceGroupRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("display_order") Integer displayOrder,
        @JsonProperty("team_selectable") Boolean teamSelectable,
        @JsonProperty("individual_selectable") Boolean individualSelectable,
        @JsonProperty("custom_level_name_i18n") Map<String, String> customLevelNameI18n,
        @JsonProperty("custom_level_options") List<ServiceGroup.CustomLevelOption> customLevelOptions) {}
