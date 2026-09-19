package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The body of {@code PUT /services/{id}/numbering-rule} and {@code PUT /service-groups/{id}/numbering-rule}. The rule
 * is replaced as a whole; a field left out takes its default (FR-CFG-018): prefix from the service group, start 1,
 * padding 3, reset daily at 00:00, separator {@code -}.
 */
public record NumberingRuleRequest(
        @JsonProperty("prefix_source") String prefixSource,
        @JsonProperty("fixed_prefix") String fixedPrefix,
        @JsonProperty("sequence_start") Long sequenceStart,
        Integer padding,
        @JsonProperty("reset_boundary") String resetBoundary,
        @JsonProperty("reset_time") String resetTime,
        String separator) {}
