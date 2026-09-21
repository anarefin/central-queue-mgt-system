package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** The body of {@code POST /reports/{key}/export}: FR-RPT-001's filters (the same reach on-screen viewing already
 * applies, FR-CFG-106) plus the format to export in (FR-RPT-003). There is no paging or sort: an export is always
 * every row the filter reaches, in the report's own stable order. */
record ReportExportRequest(
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
        String format) {

    DetailedTokenReportFilter filter() {
        return new DetailedTokenReportFilter(from, to, siteId, zoneId, serviceGroupId, serviceId, agentId, priorityClassId, channel, visitorCategory);
    }
}
