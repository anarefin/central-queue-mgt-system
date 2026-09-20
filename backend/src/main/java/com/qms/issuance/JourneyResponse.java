package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Journey as {@code POST /journeys} answers it (FR-ISS-022, ADR-0007): the one Visit every stop's ticket shares, and
 * every stop in order. A stop not yet issued (an ordered Journey's stops after the first, FR-QUE-061) has no
 * {@code ticket} and its {@code state} is {@code planned}. For an unordered Journey, {@code soonest} marks the one
 * issued stop the visitor is shown as callable soonest (FR-QUE-062): the one with the best place in its own queue.
 */
public record JourneyResponse(@JsonProperty("visit_id") UUID visitId, boolean ordered, List<Stop> stops) {

    public record Stop(
            int seq,
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("service_names") Map<String, String> serviceNames,
            String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) TicketResponse ticket,
            boolean soonest) {}
}
