package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

record CreateOutcomeCodeRequest(
        String code,
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        @JsonProperty("display_order") Integer displayOrder) {}
