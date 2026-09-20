package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code PUT /devices/{id}/display-config} (ticket 28, FR-DSP-001, FR-DSP-002): a null field keeps this
 * build's default rather than the device's current value, the same "null means default" convention as
 * {@code CreatePairingCodeRequest}'s label.
 */
record DisplayConfigRequest(
        String layout,
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("next_n") Integer nextN,
        @JsonProperty("highlight_seconds") Integer highlightSeconds,
        List<String> columns,
        AssignmentRequest assignment) {

    /** {@code scope} is {@code zone}, {@code counters} or {@code queues}; {@code ids} is empty/omitted for {@code zone}. */
    record AssignmentRequest(String scope, List<UUID> ids) {}
}
