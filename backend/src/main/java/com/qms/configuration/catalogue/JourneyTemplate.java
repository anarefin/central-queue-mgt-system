package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A Journey template (ticket 31, FR-QUE-060): an ordered or unordered set of Service stops, scoped to one Service group. */
public record JourneyTemplate(
        UUID id,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        boolean ordered,
        @JsonProperty("display_order") int displayOrder,
        boolean active,
        List<Stop> stops) {

    public record Stop(int seq, @JsonProperty("service_id") UUID serviceId, @JsonProperty("service_names") Map<String, String> serviceNames) {}
}
