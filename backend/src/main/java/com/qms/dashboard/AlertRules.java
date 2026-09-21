package com.qms.dashboard;

import com.qms.platform.alerts.ThresholdAlertTypes;
import com.qms.platform.notifications.NotificationTriggerKeys;

/**
 * What a threshold type maps onto, without a database (SRS §14.2, §15.4, ticket 47). The trigger catalogue has no
 * dedicated entry for {@code no_show_rate} (§14.2 lists only queue SLA breach, break overrun, counter unattended and
 * kiosk/display offline); a no-show-rate breach is a service-quality signal of the same kind a queue-length or
 * longest-wait breach already is, so it fires the same {@code queue_sla_breach} staff alert.
 */
final class AlertRules {

    private AlertRules() {}

    /** The notification trigger a threshold type's breach fires (FR-NTF-*, §14.2). */
    static String triggerKeyFor(String thresholdType) {
        return switch (thresholdType) {
            case ThresholdAlertTypes.QUEUE_LENGTH, ThresholdAlertTypes.LONGEST_WAIT, ThresholdAlertTypes.NO_SHOW_RATE -> NotificationTriggerKeys.QUEUE_SLA_BREACH;
            case ThresholdAlertTypes.IDLE_COUNTERS -> NotificationTriggerKeys.COUNTER_UNATTENDED;
            case ThresholdAlertTypes.DEVICE_OFFLINE -> NotificationTriggerKeys.KIOSK_DISPLAY_OFFLINE;
            case ThresholdAlertTypes.BREAK_OVERRUN -> NotificationTriggerKeys.AGENT_BREAK_OVERRUN;
            default -> throw new IllegalArgumentException("Unknown threshold type: " + thresholdType);
        };
    }
}
