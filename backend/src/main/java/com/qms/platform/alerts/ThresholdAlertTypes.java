package com.qms.platform.alerts;

/**
 * The fixed set of threshold-alert types (SRS §15.4 FR-MON-020, §11.3 FR-AGT-023, ticket 47), shared as plain
 * strings so a caller such as {@code com.qms.session.BreakOverrunScheduler} can name one without depending on
 * {@code com.qms.dashboard} (ArchitectureTest's package-cycle rule), the same reason {@code NotificationTriggerKeys}
 * is shared from {@code com.qms.platform.notifications}. The first five are configured per Service
 * ({@code service_alert_threshold}, FR-MON-020); {@code BREAK_OVERRUN} has no Service of its own — a break belongs
 * to an Agent's counter session — and is instead evaluated against its {@code break_type}'s own maximum duration
 * (FR-AGT-020, FR-AGT-023).
 */
public final class ThresholdAlertTypes {

    public static final String QUEUE_LENGTH = "queue_length";
    public static final String LONGEST_WAIT = "longest_wait";
    public static final String IDLE_COUNTERS = "idle_counters";
    public static final String NO_SHOW_RATE = "no_show_rate";
    public static final String DEVICE_OFFLINE = "device_offline";
    public static final String BREAK_OVERRUN = "break_overrun";

    private ThresholdAlertTypes() {}
}
