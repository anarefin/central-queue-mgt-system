package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** The code itself never changes, so reports keep meaning what they meant; only its labels and order do. */
record UpdateOutcomeCodeRequest(
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        @JsonProperty("display_order") Integer displayOrder) {}
