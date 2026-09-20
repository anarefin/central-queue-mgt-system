package com.qms.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Body of {@code PUT /devices/{id}/display-config}'s response: the display's configuration as it now stands. */
record DisplayConfigResponse(
        UUID id,
        String layout,
        @JsonProperty("language_cycle") List<String> languageCycle,
        @JsonProperty("next_n") int nextN,
        @JsonProperty("highlight_seconds") int highlightSeconds,
        List<String> columns,
        Assignment assignment) {

    record Assignment(String scope, List<UUID> ids) {}

    static DisplayConfigResponse from(Device device) {
        return new DisplayConfigResponse(
                device.id(),
                device.layout(),
                device.languageCycle(),
                device.nextN(),
                device.highlightSeconds(),
                device.columns(),
                new Assignment(device.assignmentScope(), device.assignmentIds()));
    }
}
