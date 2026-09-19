package com.qms.issuance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The computed order of one queue with every term of every score, so ordering can be checked without serving anyone
 * (FR-QUE-023). Terms are minutes rounded to two decimals; the order itself is computed unrounded. The score is the
 * {@code weighted_wait} sum under every strategy, but only {@code weighted_wait} orders by it; {@code strategy} says
 * which one produced {@code position}.
 */
public record QueueDryRun(
        NameRef service,
        @JsonProperty("site_id") UUID siteId,
        String strategy,
        @JsonProperty("computed_at") Instant computedAt,
        @JsonProperty("waiting_count") int waitingCount,
        List<Item> tickets) {

    public record Item(
            UUID id,
            @JsonProperty("token_number") String tokenNumber,
            String state,
            int position,
            @JsonProperty("priority_class") NameRef priorityClass,
            @JsonProperty("max_wait_minutes") Integer maxWaitMinutes,
            @JsonProperty("queued_at") Instant queuedAt,
            Terms terms,
            double score,
            boolean escalated) {}

    public record Terms(
            @JsonProperty("effective_wait_minutes") double effectiveWaitMinutes,
            @JsonProperty("headstart_minutes") double headstartMinutes,
            @JsonProperty("appointment_bonus") double appointmentBonus,
            @JsonProperty("escalation_bonus") double escalationBonus,
            @JsonProperty("score_adjustment_minutes") double scoreAdjustmentMinutes,
            @JsonProperty("adjustment_overridden") boolean adjustmentOverridden) {}
}
