package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /reports/detailed-token/run}: FR-RPT-001's filters plus server-side paging and sort
 * (FR-RPT-002). A missing body (no filter, first page, default sort) is the same as an empty one.
 */
record DetailedTokenReportRequest(
        Instant from,
        Instant to,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("zone_id") UUID zoneId,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("agent_id") UUID agentId,
        @JsonProperty("priority_class_id") UUID priorityClassId,
        String channel,
        @JsonProperty("visitor_category") String visitorCategory,
        Integer page,
        Integer size,
        String sort,
        String direction) {

    static final DetailedTokenReportRequest EMPTY =
            new DetailedTokenReportRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    DetailedTokenReportFilter filter() {
        return new DetailedTokenReportFilter(from, to, siteId, zoneId, serviceGroupId, serviceId, agentId, priorityClassId, channel, visitorCategory);
    }
}
