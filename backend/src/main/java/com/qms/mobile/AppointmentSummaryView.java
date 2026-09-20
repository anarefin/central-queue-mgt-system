package com.qms.mobile;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

/** One of a registered visitor's own appointments, past or upcoming (FR-MOB-002): the same shape reschedule/cancel act on. */
public record AppointmentSummaryView(
        UUID id,
        @JsonProperty("reference_code") String referenceCode,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("service_names") Map<String, String> serviceNames,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("site_name") String siteName,
        String date,
        String start,
        String end,
        String state) {}
