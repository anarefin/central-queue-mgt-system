package com.qms.mobile;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** What {@code /remote-join/*} sends and receives (ticket 42, SRS §13.2). */
final class RemoteJoinViews {

    private RemoteJoinViews() {}

    /**
     * A Service's remote-join policy, shown before the visitor joins (FR-MOB-023): {@code max_distance_m} null means
     * the distance check is off. When {@code virtual_queue_enabled} is false, joining is refused outright.
     */
    record PolicyView(
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("virtual_queue_enabled") boolean virtualQueueEnabled,
            @JsonProperty("max_distance_m") Integer maxDistanceMeters,
            @JsonProperty("max_remote_share_pct") int maxRemoteSharePct,
            @JsonProperty("join_window_minutes") int joinWindowMinutes,
            @JsonProperty("arrival_deadline_minutes") int arrivalDeadlineMinutes) {}

    /** The visitor's own device position at the moment of joining; required only when the policy sets a distance cap. */
    record JoinRequest(Double latitude, Double longitude) {}
}
