package com.qms.platform.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param heartbeatInterval how often each side sends a heartbeat (§21.1: 20 seconds)
 * @param missedHeartbeats how many the hub lets a client miss before it drops the connection
 * @param replayWindow how far back a topic keeps its events for a reconnecting client (FR-QUE-081: at least 5 minutes)
 * @param replayEvents how many events a topic keeps at least, however old (FR-QUE-081: at least 1,000)
 */
@ConfigurationProperties("qms.realtime")
public record RealtimeProperties(
        @DefaultValue("20s") Duration heartbeatInterval,
        @DefaultValue("2") int missedHeartbeats,
        @DefaultValue("5m") Duration replayWindow,
        @DefaultValue("1000") int replayEvents) {

    public RealtimeProperties {
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) throw new IllegalArgumentException("qms.realtime.heartbeat-interval must be positive");
        if (missedHeartbeats < 1) throw new IllegalArgumentException("qms.realtime.missed-heartbeats must be at least 1");
        if (replayWindow.isNegative()) throw new IllegalArgumentException("qms.realtime.replay-window must not be negative");
        if (replayEvents < 0) throw new IllegalArgumentException("qms.realtime.replay-events must not be negative");
    }

    public static RealtimeProperties defaults() {
        return new RealtimeProperties(Duration.ofSeconds(20), 2, Duration.ofMinutes(5), 1000);
    }
}
