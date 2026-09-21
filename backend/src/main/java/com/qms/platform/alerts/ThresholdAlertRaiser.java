package com.qms.platform.alerts;

/**
 * The one seam a bounded context reports a threshold breach through (SRS §15.4, §11.3 FR-AGT-023, ticket 47):
 * implemented once, by {@code com.qms.dashboard.AlertEvaluationService}, so {@code com.qms.session}'s break-overrun
 * sweep never learns how grouping (FR-MON-023), escalation (FR-MON-021) or notifying the relevant Team Admin work —
 * the same separation {@link com.qms.platform.notifications.NotificationTrigger} already gives the notification
 * pipeline, and {@link com.qms.platform.realtime.RealtimePublisher} the realtime hub.
 */
public interface ThresholdAlertRaiser {

    void raise(ThresholdAlertContext context);
}
