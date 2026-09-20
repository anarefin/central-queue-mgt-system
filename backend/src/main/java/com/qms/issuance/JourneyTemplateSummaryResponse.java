package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A Journey template as Reception's picker sees it (FR-QUE-060): active, offered at the caller's Site, with its stops in order. */
public record JourneyTemplateSummaryResponse(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, boolean ordered, List<Stop> stops) {

    public record Stop(int seq, @JsonProperty("service_id") UUID serviceId, @JsonProperty("service_names") Map<String, String> serviceNames) {}

    static JourneyTemplateSummaryResponse of(JourneyRepository.TemplateSummary t) {
        return new JourneyTemplateSummaryResponse(
                t.id(), t.nameI18n(), t.ordered(), t.stops().stream().map(s -> new Stop(s.seq(), s.serviceId(), s.serviceNames())).toList());
    }
}
