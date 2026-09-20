package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code service_ids} is the template's stops, in order (at least two, FR-QUE-060). */
record CreateJourneyTemplateRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        boolean ordered,
        @JsonProperty("display_order") Integer displayOrder,
        @JsonProperty("service_ids") List<UUID> serviceIds) {}
