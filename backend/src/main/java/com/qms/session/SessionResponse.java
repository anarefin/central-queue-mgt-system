package com.qms.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A counter session as the console shows it (SRS §11, §19.3): who sits where, which Services they serve, and the ticket
 * in progress. Everything the console needs to restore itself after a refresh is here (FR-AGT-004): the console holds no
 * state the server does not, and {@code ticket.version} is what it sends back as {@code If-Match} (§20.1).
 */
public record SessionResponse(
        UUID id,
        CounterRef counter,
        @JsonProperty("agent_id") UUID agentId,
        String state,
        @JsonProperty("opened_at") Instant openedAt,
        @JsonProperty("closed_at") Instant closedAt,
        List<ServiceRef> services,
        SessionTicket ticket) {

    public record CounterRef(UUID id, String label, @JsonProperty("zone_id") UUID zoneId, @JsonProperty("zone_name") String zoneName, @JsonProperty("site_id") UUID siteId) {}

    /** A Service a counter serves, with the weight of its link: 1 is primary, higher is a fallback (FR-CFG-011). */
    public record ServiceRef(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n, @JsonProperty("preference_weight") int preferenceWeight) {}

    public record Named(UUID id, @JsonProperty("name_i18n") Map<String, String> nameI18n) {}

    /** What an agent may record when completing this ticket's Service (FR-AGT-032). */
    public record Outcome(UUID id, String code, @JsonProperty("label_i18n") Map<String, String> labelI18n) {}

    /**
     * The ticket bound to the session, {@code called} or {@code serving}. {@code waitSeconds} is how long it waited in the
     * queue before it was called.
     */
    public record SessionTicket(
            UUID id,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            int version,
            Named service,
            @JsonProperty("origin_channel") String originChannel,
            @JsonProperty("priority_class") Named priorityClass,
            @JsonProperty("queued_at") Instant queuedAt,
            @JsonProperty("called_at") Instant calledAt,
            @JsonProperty("served_at") Instant servedAt,
            @JsonProperty("wait_seconds") int waitSeconds,
            List<Outcome> outcomes) {}
}
