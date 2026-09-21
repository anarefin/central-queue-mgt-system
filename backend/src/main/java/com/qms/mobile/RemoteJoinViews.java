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
    /**
     * {@code internet_available} is the Site-wide reachability check (ticket 44, FR-QUE-202, FR-MOB-041): false
     * means remote join is shown as temporarily unavailable, with an explanation, before the visitor ever tries to
     * join — {@code POST} refuses it too ({@code internet_unreachable}), but this lets the screen say so up front,
     * the same "shown before {@code POST} enforces it again" shape {@code virtual_queue_enabled} already is.
     */
    record PolicyView(
            @JsonProperty("service_id") UUID serviceId,
            @JsonProperty("virtual_queue_enabled") boolean virtualQueueEnabled,
            @JsonProperty("max_distance_m") Integer maxDistanceMeters,
            @JsonProperty("max_remote_share_pct") int maxRemoteSharePct,
            @JsonProperty("join_window_minutes") int joinWindowMinutes,
            @JsonProperty("arrival_deadline_minutes") int arrivalDeadlineMinutes,
            @JsonProperty("internet_available") boolean internetAvailable) {}

    /** The visitor's own device position at the moment of joining; required only when the policy sets a distance cap. */
    record JoinRequest(Double latitude, Double longitude) {}
}
