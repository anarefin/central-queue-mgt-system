package com.qms.configuration.priority;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A class of visitor that queues ahead of others (e.g. senior citizen, emergency). It grants a Head start in minutes of
 * virtual waiting on arrival (normal = 0) and may set the maximum wait after which its tickets are escalated
 * (FR-QUE-010, FR-QUE-022, ADR-0003). {@code isDefault} marks the normal class every ticket without a class belongs to.
 */
public record PriorityClass(
        UUID id,
        @JsonProperty("name_i18n") Map<String, String> nameI18n,
        @JsonProperty("headstart_minutes") int headstartMinutes,
        @JsonProperty("max_wait_minutes") Integer maxWaitMinutes,
        @JsonProperty("token_prefix_override") String tokenPrefixOverride,
        @JsonProperty("is_default") boolean isDefault,
        boolean active,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
