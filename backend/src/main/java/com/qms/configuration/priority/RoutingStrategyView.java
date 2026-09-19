package com.qms.configuration.priority;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** The ordering strategy of a Service group and the ones it can choose from (FR-QUE-021). */
public record RoutingStrategyView(
        @JsonProperty("service_group_id") UUID serviceGroupId,
        String strategy,
        @JsonProperty("is_default") boolean isDefault,
        List<String> available) {}
