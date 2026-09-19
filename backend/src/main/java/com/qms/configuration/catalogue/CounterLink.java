package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** A counter that serves a service; weight 1 is the primary counter and a higher weight a fallback (FR-CFG-011). */
public record CounterLink(
        @JsonProperty("counter_id") UUID counterId,
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("preference_weight") int preferenceWeight,
        @JsonProperty("counter_label") String counterLabel,
        @JsonProperty("counter_active") boolean counterActive) {}
