package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /tickets}. {@code origin_channel} defaults to the caller's channel; {@code occurred_at} is the
 * device's own time of the request and defaults to the server's.
 */
public record IssueTicketRequest(
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("origin_channel") String originChannel,
        @JsonProperty("occurred_at") Instant occurredAt) {}
