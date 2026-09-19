package com.qms.configuration.priority;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The whole editable content of a Priority class, for create and for replace. An absent {@code max_wait_minutes} or
 * {@code token_prefix_override} means none. Validated by {@link PriorityRules}, so a failure names the wire field.
 */
public record PriorityClassRequest(
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("headstart_minutes") Integer headstartMinutes,
        @JsonProperty("max_wait_minutes") Integer maxWaitMinutes,
        @JsonProperty("token_prefix_override") String tokenPrefixOverride) {}
