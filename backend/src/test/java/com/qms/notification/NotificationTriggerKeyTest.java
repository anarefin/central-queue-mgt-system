package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SRS §14.2's trigger catalogue, transcribed independently of the enum (no database, NFR-MNT-004). */
class NotificationTriggerKeyTest {

    @Test
    void everyTriggerKeyIsFindableByItsWireKey() {
        for (NotificationTriggerKey trigger : NotificationTriggerKey.values()) {
            assertThat(NotificationTriggerKey.fromKey(trigger.key())).contains(trigger);
        }
    }

    @Test
    void anUnknownKeyIsNotFound() {
        assertThat(NotificationTriggerKey.fromKey("not_a_trigger")).isEmpty();
    }

    @Test
    void defaultChannelOrdersMatchTheCatalogueTable() {
        assertThat(NotificationTriggerKey.TICKET_ISSUED.defaultChannelOrder()).isEqualTo(List.of("web_push", "in_app"));
        assertThat(NotificationTriggerKey.YOUR_TURN.defaultChannelOrder()).isEqualTo(List.of("web_push", "in_app"));
        assertThat(NotificationTriggerKey.APPOINTMENT_CONFIRMED.defaultChannelOrder()).isEqualTo(List.of("email", "web_push"));
        assertThat(NotificationTriggerKey.WAITLIST_SLOT_OFFERED.defaultChannelOrder()).isEqualTo(List.of("web_push", "email"));
        assertThat(NotificationTriggerKey.QUEUE_SLA_BREACH.defaultChannelOrder()).isEqualTo(List.of("staff_alert"));
    }

    @Test
    void serviceCompletedFeedbackStartsOffEveryOtherQueueTriggerStartsOn() {
        assertThat(NotificationTriggerKey.SERVICE_COMPLETED_FEEDBACK.defaultEnabled("mobile")).isFalse();
        assertThat(NotificationTriggerKey.YOUR_TURN.defaultEnabled("mobile")).isTrue();
        assertThat(NotificationTriggerKey.MISSED_BACK_IN_QUEUE.defaultEnabled("kiosk")).isTrue();
    }

    @Test
    void ticketIssuedDefaultsOnOnlyForARemoteJoinAndOffForEveryOtherOriginChannel() {
        assertThat(NotificationTriggerKey.TICKET_ISSUED.defaultEnabled("mobile")).isTrue();
        assertThat(NotificationTriggerKey.TICKET_ISSUED.defaultEnabled("kiosk")).isFalse();
        assertThat(NotificationTriggerKey.TICKET_ISSUED.defaultEnabled("reception")).isFalse();
        assertThat(NotificationTriggerKey.TICKET_ISSUED.defaultEnabled("appointment_checkin")).isFalse();
        assertThat(NotificationTriggerKey.TICKET_ISSUED.defaultEnabled(null)).isFalse();
    }

    @Test
    void essentialTriggersBypassQuietHoursAndOptOut() {
        assertThat(NotificationTriggerKey.YOUR_TURN.essential()).isTrue();
        assertThat(NotificationTriggerKey.MISSED_BACK_IN_QUEUE.essential()).isTrue();
        assertThat(NotificationTriggerKey.QUEUE_SLA_BREACH.essential()).isTrue();
        assertThat(NotificationTriggerKey.TICKET_ISSUED.essential()).isFalse();
        assertThat(NotificationTriggerKey.MARKED_NO_SHOW.essential()).isFalse();
    }

    @Test
    void everyTriggerHasAtLeastOneAllowedTemplateVariable() {
        for (NotificationTriggerKey trigger : NotificationTriggerKey.values()) {
            assertThat(trigger.variables()).as(trigger.key()).isNotEmpty();
        }
    }
}
