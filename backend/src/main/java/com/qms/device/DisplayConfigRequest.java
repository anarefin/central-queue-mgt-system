package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code PUT /devices/{id}/display-config} (ticket 28/30, FR-DSP-001..003, FR-I18N-005): a null field keeps
 * this build's default rather than the device's current value, the same "null means default" convention as
 * {@code CreatePairingCodeRequest}'s label. {@code layoutConfig} is the zone-proportion setting the chosen
 * {@code layout} needs, if any; {@code languageCycleSeconds} is how often the display rotates through
 * {@code languageCycle} (0 renders it side by side instead).
 */
record DisplayConfigRequest(
        String layout,
        @JsonProperty("layout_config") Map<String, Object> layoutConfig,
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("language_cycle_seconds") Integer languageCycleSeconds,
        @JsonProperty("next_n") Integer nextN,
        @JsonProperty("highlight_seconds") Integer highlightSeconds,
        List<String> columns,
        AssignmentRequest assignment) {

    /** {@code scope} is {@code zone}, {@code counters} or {@code queues}; {@code ids} is empty/omitted for {@code zone}. */
    record AssignmentRequest(String scope, List<UUID> ids) {}
}
