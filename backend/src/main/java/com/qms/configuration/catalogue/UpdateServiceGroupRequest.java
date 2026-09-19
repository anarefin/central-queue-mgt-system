package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Absent (null) fields are left unchanged; {@code name_i18n}, when given, replaces the whole set of names. */
record UpdateServiceGroupRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("display_order") Integer displayOrder) {}
