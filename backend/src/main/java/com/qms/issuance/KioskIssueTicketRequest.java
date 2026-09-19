package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * The body of {@code POST /kiosk/tickets}: the visitor's chosen Service, and the rest of the selection tree they
 * walked (ticket 26, FR-ISS-010, §8.2). The channel, actor and time are the device's own. {@code visitorId} is who
 * {@code GET /kiosk/visitors/identify} resolved them to, or null when they chose (or were left with) no
 * identification. {@code agentId} is the on-duty Agent picked at the individual level, or null for anyone on the
 * Service's team. {@code customLevelId} is the option picked at the group's custom level, or null when it has none
 * or the visitor skipped it.
 */
public record KioskIssueTicketRequest(
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("visitor_id") UUID visitorId,
        @JsonProperty("agent_id") UUID agentId,
        @JsonProperty("custom_level_id") String customLevelId) {}
