package com.qms.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Body of {@code PUT /services/{id}/alert-thresholds} (FR-MON-020). Every field is optional; a field left out (or
 * given as null) leaves that metric unmonitored. */
public record AlertThresholdRequest(
        @JsonProperty("queue_length_max") Integer queueLengthMax,
        @JsonProperty("longest_wait_minutes_max") Integer longestWaitMinutesMax,
        @JsonProperty("idle_counters_with_queue_max") Integer idleCountersWithQueueMax,
        @JsonProperty("no_show_rate_percent_max") BigDecimal noShowRatePercentMax,
        @JsonProperty("device_offline_minutes_max") Integer deviceOfflineMinutesMax,
        @JsonProperty("group_window_minutes") Integer groupWindowMinutes,
        @JsonProperty("escalation_delay_minutes") Integer escalationDelayMinutes) {}
