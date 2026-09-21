package com.qms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The scope a schedule reaches: FR-RPT-001's filters minus {@code from}/{@code to} — a recurring schedule computes
 * its own report window fresh on every run from its own {@link ReportScheduleCadence}, never a fixed range. A field
 * a given report key ignores is simply left out of that key's own request, the same rule {@link ReportRunRequest}'s
 * own class comment already documents for the catalogue as a whole.
 */
record ReportScheduleFilter(
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("zone_id") UUID zoneId,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("agent_id") UUID agentId,
        @JsonProperty("priority_class_id") UUID priorityClassId,
        String channel,
        @JsonProperty("visitor_category") String visitorCategory) {

    static final ReportScheduleFilter EMPTY = new ReportScheduleFilter(null, null, null, null, null, null, null, null);

    /** The request {@code ReportRunService#run} takes, for one run of this schedule over {@code [from, to)}. */
    ReportRunRequest asRunRequest(Instant from, Instant to, int size) {
        return new ReportRunRequest(
                from, to, siteId, zoneId, serviceGroupId, serviceId, agentId, priorityClassId, channel, visitorCategory,
                null, null, 0, size, null, null, null);
    }
}
