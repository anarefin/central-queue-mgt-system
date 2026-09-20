package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Absent (null) fields are left unchanged; an empty {@code icon} clears it. */
record UpdateServiceRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("expected_minutes") Integer expectedMinutes,
        @JsonProperty("sla_wait_minutes") Integer slaWaitMinutes,
        List<String> channels,
        String icon,
        @JsonProperty("display_order") Integer displayOrder,
        @JsonProperty("visitor_identifier") String visitorIdentifier,
        @JsonProperty("booking_mode") String bookingMode,
        @JsonProperty("parallel_serving") Boolean parallelServing,
        @JsonProperty("parallel_limit") Integer parallelLimit,
        @JsonProperty("announce_visitor_name") Boolean announceVisitorName) {}
