package com.qms.integration.serviceaccount;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * The body of {@code POST /host/tickets} (ticket 58, FR-INT-030): the Service the host system's own visitor picked,
 * the visitor it already resolved (or knows nothing of), and its own free-text note. Unlike the staff and kiosk
 * adapters there is no priority class or target Agent here: routing stays a staff/admin decision, not a client
 * integration's.
 */
public record HostSystemIssueTicketRequest(
        @JsonProperty("service_id") UUID serviceId, @JsonProperty("visitor_id") UUID visitorId, @JsonProperty("purpose_note") String purposeNote) {}
