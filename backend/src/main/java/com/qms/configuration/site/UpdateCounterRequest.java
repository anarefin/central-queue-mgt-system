package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Absent (null) fields are left unchanged; an empty {@code location_note} clears it. Validated by {@link SiteRules}. */
record UpdateCounterRequest(String label, @JsonProperty("location_note") String locationNote) {}
