package com.qms.mobile;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

/** One of a registered visitor's own active tickets (FR-MOB-002). Never carries the ticket's secret: the visitor is
 * already authenticated by their own JWT, and the anonymous ticket page (ticket 37) is a separate path entirely. */
public record TicketSummaryView(
        UUID id,
        @JsonProperty("token_number") String tokenNumber,
        String state,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("service_names") Map<String, String> serviceNames,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("site_name") String siteName,
        @JsonProperty("issued_at") String issuedAt) {}
