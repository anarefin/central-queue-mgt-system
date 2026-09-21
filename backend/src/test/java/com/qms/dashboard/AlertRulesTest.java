package com.qms.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.alerts.ThresholdAlertTypes;
import com.qms.platform.notifications.NotificationTriggerKeys;
import org.junit.jupiter.api.Test;

/** {@link AlertRules} without a database (SRS §14.2, §15.4, ticket 47). */
class AlertRulesTest {

    @Test
    void queueLengthLongestWaitAndNoShowRateAllFireTheSlaBreachTrigger() {
        assertThat(AlertRules.triggerKeyFor(ThresholdAlertTypes.QUEUE_LENGTH)).isEqualTo(NotificationTriggerKeys.QUEUE_SLA_BREACH);
        assertThat(AlertRules.triggerKeyFor(ThresholdAlertTypes.LONGEST_WAIT)).isEqualTo(NotificationTriggerKeys.QUEUE_SLA_BREACH);
        assertThat(AlertRules.triggerKeyFor(ThresholdAlertTypes.NO_SHOW_RATE)).isEqualTo(NotificationTriggerKeys.QUEUE_SLA_BREACH);
    }

    @Test
    void idleCountersDeviceOfflineAndBreakOverrunEachHaveTheirOwnCatalogueTrigger() {
        assertThat(AlertRules.triggerKeyFor(ThresholdAlertTypes.IDLE_COUNTERS)).isEqualTo(NotificationTriggerKeys.COUNTER_UNATTENDED);
        assertThat(AlertRules.triggerKeyFor(ThresholdAlertTypes.DEVICE_OFFLINE)).isEqualTo(NotificationTriggerKeys.KIOSK_DISPLAY_OFFLINE);
        assertThat(AlertRules.triggerKeyFor(ThresholdAlertTypes.BREAK_OVERRUN)).isEqualTo(NotificationTriggerKeys.AGENT_BREAK_OVERRUN);
    }

    @Test
    void anUnknownThresholdTypeIsRejected() {
        assertThatThrownBy(() -> AlertRules.triggerKeyFor("not_a_real_type")).isInstanceOf(IllegalArgumentException.class);
    }
}
