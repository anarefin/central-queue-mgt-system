package com.qms.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One Service's own alert thresholds (SRS §15.4, FR-MON-020): each left null leaves that metric unmonitored for the
 * Service. {@code groupWindowMinutes} and {@code escalationDelayMinutes} override {@link AlertProperties}' own
 * defaults for this Service's alerts (FR-MON-021, FR-MON-023); null keeps the default.
 */
public record AlertThreshold(
        @JsonProperty("service_id") UUID serviceId,
        @JsonProperty("queue_length_max") Integer queueLengthMax,
        @JsonProperty("longest_wait_minutes_max") Integer longestWaitMinutesMax,
        @JsonProperty("idle_counters_with_queue_max") Integer idleCountersWithQueueMax,
        @JsonProperty("no_show_rate_percent_max") BigDecimal noShowRatePercentMax,
        @JsonProperty("device_offline_minutes_max") Integer deviceOfflineMinutesMax,
        @JsonProperty("group_window_minutes") Integer groupWindowMinutes,
        @JsonProperty("escalation_delay_minutes") Integer escalationDelayMinutes,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("updated_by") UUID updatedBy) {

    /** An all-null row (every metric unmonitored), what a Service with no configuration yet reads as. */
    static AlertThreshold empty(UUID serviceId) {
        return new AlertThreshold(serviceId, null, null, null, null, null, null, null, null, null);
    }
}
