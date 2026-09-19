package com.qms.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** The §20.3 error envelope: {@code {"error": {code, message, message_i18n, details, trace_id}}}. */
public record ApiError(Body error) {

    public record Body(
            String code,
            String message,
            @JsonProperty("message_i18n") Map<String, String> messageI18n,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details,
            @JsonProperty("trace_id") String traceId) {}
}
