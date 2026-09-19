package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the next Token number would be, per Service, without issuing anything or using up a number. {@code rule_source}
 * says which rule produced it: {@code service}, {@code service_group} or the built-in {@code default}.
 */
public record NumberingPreview(List<Item> items) {

    public record Item(
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("token_number") String tokenNumber,
            String prefix,
            long sequence,
            @JsonProperty("reset_key") String resetKey,
            /** When the current period ends and the sequence starts over; null for a rule that never resets. */
            @JsonProperty("next_reset_at") Instant nextResetAt,
            @JsonProperty("rule_source") String ruleSource) {}
}
