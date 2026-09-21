package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /reports/{key}/run} (ticket 48/49/50): a superset of every field any report key in the
 * catalogue accepts. FR-RPT-001's nine filters plus {@code detailed-token}'s own paging/sort (ticket 48), the
 * {@code break} report's own break type (pre-existing, ticket 16) and the operational reports' own grain (ticket 50,
 * §16.1's "Hour/day/month/year"). One shared shape keeps the endpoint single, as {@code ReportRunService}'s own
 * comment already anticipated ("Later tickets (49, 50, 51) grow the catalogue"); a field a given key ignores is
 * simply left out of that key's own request record when it is built.
 */
record ReportRunRequest(
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
        @JsonProperty("break_type_id") UUID breakTypeId,
        String grain,
        Integer page,
        Integer size,
        String sort,
        String direction,
        /** {@code audit}'s own cursor (ticket 51): the one key in the catalogue whose read path
         * ({@link com.qms.audit.AuditQueryService}) pages by an opaque cursor rather than {@code page}/{@code size},
         * the same "a field a given key ignores is simply left out" growth this record's own class comment already
         * documents. */
        String cursor) {

    static final ReportRunRequest EMPTY =
            new ReportRunRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    DetailedTokenReportRequest asDetailedToken() {
        return new DetailedTokenReportRequest(
                from, to, siteId, zoneId, serviceGroupId, serviceId, agentId, priorityClassId, channel, visitorCategory, page, size, sort, direction);
    }

    DetailedTokenReportFilter filter() {
        return new DetailedTokenReportFilter(from, to, siteId, zoneId, serviceGroupId, serviceId, agentId, priorityClassId, channel, visitorCategory);
    }
}
