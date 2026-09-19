package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.UUID;

/** A numbering rule as the API shows it (FR-CFG-018). */
public record NumberingRuleView(
        UUID id,
        @JsonProperty("site_id") UUID siteId,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("scope_id") UUID scopeId,
        @JsonProperty("prefix_source") String prefixSource,
        @JsonProperty("fixed_prefix") String fixedPrefix,
        @JsonProperty("sequence_start") long sequenceStart,
        int padding,
        @JsonProperty("reset_boundary") String resetBoundary,
        @JsonProperty("reset_time") String resetTime,
        String separator,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    static NumberingRuleView of(NumberingRule rule) {
        NumberingSpec s = rule.spec();
        return new NumberingRuleView(
                rule.id(),
                rule.siteId(),
                rule.scopeType(),
                rule.scopeId(),
                s.prefixSource(),
                s.fixedPrefix(),
                s.start(),
                s.padding(),
                s.boundary().wire(),
                s.resetTime().format(TIME),
                s.separator(),
                rule.createdAt(),
                rule.updatedAt());
    }
}
