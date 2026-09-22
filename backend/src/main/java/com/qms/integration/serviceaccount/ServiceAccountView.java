package com.qms.integration.serviceaccount;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A service account as the admin API shows it. {@code clientSecret} is present only in the response to the request
 * that generated it (creation): only its bcrypt hash is stored, so it can never be read back (the same convention
 * {@code issuance.TicketResponse#secret} and {@code integration.webhook.WebhookEndpointView#secret} already are). */
public record ServiceAccountView(
        UUID id,
        @JsonProperty("client_id") String clientId,
        String label,
        @JsonProperty("site_ids") Set<UUID> siteIds,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("client_secret") @JsonInclude(JsonInclude.Include.NON_NULL) String clientSecret) {

    ServiceAccountView withClientSecret(String clientSecret) {
        return new ServiceAccountView(id, clientId, label, siteIds, active, createdAt, updatedAt, clientSecret);
    }
}
