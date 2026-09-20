package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * The body of {@code POST /journeys} (FR-ISS-022): either {@code journey_template_id} names a template (FR-QUE-060),
 * whose own {@code ordered} flag and stops are used and {@code service_ids}/{@code ordered} are ignored, or
 * {@code service_ids} lists an ad hoc Journey's stops, in the order Reception picked them, with {@code ordered}
 * required to say which of FR-QUE-061/FR-QUE-062 applies. {@code priority_class_id} is chosen once for the whole
 * Journey and, for an ordered one, inherited by every later stop (FR-QUE-061); its absence means the default class.
 * {@code visitor_id} and {@code purpose_note} carry through to every stop issued now, the same as a single ticket.
 */
public record IssueJourneyRequest(
        @JsonProperty("journey_template_id") UUID journeyTemplateId,
        @JsonProperty("service_ids") List<UUID> serviceIds,
        Boolean ordered,
        @JsonProperty("priority_class_id") UUID priorityClassId,
        @JsonProperty("visitor_id") UUID visitorId,
        @JsonProperty("purpose_note") String purposeNote) {}
