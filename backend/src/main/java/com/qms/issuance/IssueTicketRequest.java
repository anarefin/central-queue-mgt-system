package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /tickets}. {@code origin_channel} defaults to the caller's channel; {@code occurred_at} is the
 * device's own time of the request and defaults to the server's; {@code priority_class_id} is the Priority class staff
 * assign, and its absence means the default (normal) class. {@code visitor_id} names the visitor the ticket is for, and
 * {@code confirm_duplicate} says the desk has seen the duplicate warning and issues anyway (FR-ISS-004). {@code
 * purpose_note} is a free-text note visible only to the agent who serves this ticket (FR-ISS-020).
 */
public record IssueTicketRequest(
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("origin_channel") String originChannel,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("priority_class_id") UUID priorityClassId,
        @JsonProperty("visitor_id") UUID visitorId,
        @JsonProperty("confirm_duplicate") Boolean confirmDuplicate,
        @JsonProperty("purpose_note") String purposeNote) {}
