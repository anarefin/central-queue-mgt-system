package com.qms.integration.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A webhook endpoint as the API shows it (FR-INT-020). {@code secret} is present only in the response to creating
 * the endpoint or rotating its secret; only its encrypted form is stored, and it is never readable back after that
 * (the same convention {@code issuance.TicketResponse.secret} already uses for a ticket's own secret).
 */
public record WebhookEndpointView(
        UUID id,
        String description,
        String url,
        @JsonProperty("event_types") List<String> eventTypes,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {

    WebhookEndpointView withSecret(String secret) {
        return new WebhookEndpointView(id, description, url, eventTypes, active, createdAt, updatedAt, secret);
    }
}
