package com.qms.configuration.site;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Validated by {@link SiteRules}, so a failure names the wire field. */
record CreateCounterRequest(String label, @JsonProperty("location_note") String locationNote) {}
