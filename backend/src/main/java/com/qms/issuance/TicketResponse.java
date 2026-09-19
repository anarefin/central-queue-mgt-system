package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.queue.WaitEstimate;
import java.time.Instant;
import java.util.UUID;

/**
 * A ticket as the API shows it (SRS §20.5). {@code position} is null once the ticket has left the queue. The
 * {@code secret} is present only in the response to the issuing request; only its hash is stored (§18.3), so it can
 * never be read back. {@code estimated_wait_minutes} is a rounded range, null once the ticket has left the queue (FR-QUE-042).
 */
public record TicketResponse(
        UUID id,
        @JsonProperty("token_number") String tokenNumber,
        String state,
        NameRef service,
        @JsonProperty("service_group") NameRef serviceGroup,
        @JsonProperty("site_id") UUID siteId,
        ZoneRef zone,
        @JsonProperty("visit_id") UUID visitId,
        @JsonProperty("origin_channel") String originChannel,
        @JsonProperty("priority_class") NameRef priorityClass,
        Integer position,
        @JsonProperty("estimated_wait_minutes") WaitEstimate estimatedWait,
        @JsonProperty("issued_at") Instant issuedAt,
        @JsonProperty("queued_at") Instant queuedAt,
        int version,
        @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {

    TicketResponse withSecret(String secret) {
        return new TicketResponse(id, tokenNumber, state, service, serviceGroup, siteId, zone, visitId, originChannel, priorityClass, position, estimatedWait, issuedAt, queuedAt, version, secret);
    }
}
