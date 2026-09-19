package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Validated by {@link CatalogueRules}, so a failure names the wire field. */
record CreateServiceGroupRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("display_order") Integer displayOrder) {}
