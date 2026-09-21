package com.qms.reporting;

import java.util.Map;

/**
 * The Notification report's "cost indicator" column (§16.1: "Trigger, channel, status, cost indicator"). This
 * system carries no per-message monetary cost anywhere (no SMS/voice provider billing exists yet, {@code
 * notification_message} itself has no cost column) — the closest honest reading is a relative tier per channel,
 * documented here rather than invented as a fabricated currency figure. {@code in_app} and {@code staff_alert} run
 * over this system's own realtime hub (free, internal); {@code web_push} and {@code email} go through an external
 * provider (a real, if small, per-message cost). A channel this map does not yet know about (a later channel, e.g.
 * SMS) reads {@code unknown} rather than silently defaulting to free.
 */
final class NotificationCostIndicator {

    private static final Map<String, String> TIER_BY_CHANNEL =
            Map.of("in_app", "free", "staff_alert", "free", "web_push", "low", "email", "low");

    private static final String UNKNOWN = "unknown";

    private NotificationCostIndicator() {}

    static String forChannel(String channel) {
        return channel == null ? UNKNOWN : TIER_BY_CHANNEL.getOrDefault(channel, UNKNOWN);
    }
}
