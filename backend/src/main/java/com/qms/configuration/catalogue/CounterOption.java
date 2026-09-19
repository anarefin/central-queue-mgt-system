package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** An active counter of the service group's site that services of the group could be linked to. */
public record CounterOption(
        UUID id,
        @JsonProperty("zone_id") UUID zoneId,
        @JsonProperty("zone_name") String zoneName,
        String label) {}
