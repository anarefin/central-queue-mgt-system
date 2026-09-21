package com.qms.notification;

import com.qms.platform.notifications.NotificationTriggerKeys;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SRS §14.2's trigger catalogue: the default channel preference order (FR-NTF-001), whether it starts enabled, the
 * fixed set of template variables it allows (FR-NTF-020, FR-NTF-021) and whether it is "essential" — bypassing
 * quiet hours (FR-NTF-031: only "non-urgent" notifications are suppressed) and opt-out (FR-NTF-035: opt-out covers
 * only "non-essential" notifications). The SRS gives no separate name for that second property, so this ticket
 * treats the two as one flag: a message important enough to interrupt quiet hours is important enough that a
 * visitor cannot silence it.
 *
 * <p>Web Push (ticket 39) and email (ticket 40) both now register their own adapters; {@link NotificationDispatcher}
 * and {@link NotificationSendWorker} still skip a channel with no registered adapter and fall to the next one
 * (FR-NTF-033), so a Phase 2 channel (SMS, native push) with nothing behind it yet costs nothing to leave catalogued
 * here already (FR-NTF-005, FR-INT-040).
 *
 * <p>The operational-alert triggers are fired by {@code com.qms.dashboard}'s own threshold sweep ({@code
 * queue_sla_breach}, {@code counter_unattended}, {@code kiosk_display_offline}, FR-MON-020..023) and {@code
 * com.qms.session}'s break-overrun sweep ({@code agent_break_overrun}, FR-AGT-023) — ticket 47. {@code approaching_turn} is fired by
 * {@code com.qms.queue.RemoteArrivalService}'s own sweep (ticket 43, FR-MOB-020), the same wait-estimate threshold
 * this catalogue already anticipated; {@code ticket_forfeited} likewise (ticket 43, FR-MOB-022). The appointment
 * triggers (ticket 40, FR-APT-050) are fired from {@code com.qms.appointment.AppointmentBookingService} and its
 * reminder scheduler.
 */
public enum NotificationTriggerKey {
    TICKET_ISSUED(NotificationTriggerKeys.TICKET_ISSUED, List.of("web_push", "in_app"), true, false, ticketVars()),
    APPROACHING_TURN(NotificationTriggerKeys.APPROACHING_TURN, List.of("web_push", "in_app"), true, false, ticketVars()),
    YOUR_TURN(NotificationTriggerKeys.YOUR_TURN, List.of("web_push", "in_app"), true, true, ticketVarsWithCounter()),
    MISSED_BACK_IN_QUEUE(NotificationTriggerKeys.MISSED_BACK_IN_QUEUE, List.of("web_push", "in_app"), true, true, ticketVars()),
    MARKED_NO_SHOW(NotificationTriggerKeys.MARKED_NO_SHOW, List.of("web_push", "in_app"), true, false, ticketVars()),
    TICKET_TRANSFERRED(NotificationTriggerKeys.TICKET_TRANSFERRED, List.of("web_push", "in_app"), true, false, ticketVars()),
    TICKET_FORFEITED(NotificationTriggerKeys.TICKET_FORFEITED, List.of("web_push", "in_app"), true, false, ticketVars()),
    APPOINTMENT_CONFIRMED(NotificationTriggerKeys.APPOINTMENT_CONFIRMED, List.of("email", "web_push"), true, false, appointmentVars()),
    APPOINTMENT_REMINDER(NotificationTriggerKeys.APPOINTMENT_REMINDER, List.of("email", "web_push"), true, false, appointmentVars()),
    APPOINTMENT_RESCHEDULED_OR_CANCELLED(NotificationTriggerKeys.APPOINTMENT_RESCHEDULED_OR_CANCELLED, List.of("email", "web_push"), true, false, appointmentVars()),
    WAITLIST_SLOT_OFFERED(NotificationTriggerKeys.WAITLIST_SLOT_OFFERED, List.of("web_push", "email"), true, true, appointmentVars()),
    SERVICE_COMPLETED_FEEDBACK(NotificationTriggerKeys.SERVICE_COMPLETED_FEEDBACK, List.of("web_push", "in_app"), false, false, ticketVars()),
    QUEUE_SLA_BREACH(NotificationTriggerKeys.QUEUE_SLA_BREACH, List.of("staff_alert"), true, true, staffAlertVars()),
    AGENT_BREAK_OVERRUN(NotificationTriggerKeys.AGENT_BREAK_OVERRUN, List.of("staff_alert"), true, true, staffAlertVars()),
    COUNTER_UNATTENDED(NotificationTriggerKeys.COUNTER_UNATTENDED, List.of("staff_alert"), true, true, staffAlertVars()),
    KIOSK_DISPLAY_OFFLINE(NotificationTriggerKeys.KIOSK_DISPLAY_OFFLINE, List.of("staff_alert"), true, true, staffAlertVars());

    private static List<String> ticketVars() {
        return List.of("token_number", "service_group_name", "service_name", "site_name");
    }

    private static List<String> ticketVarsWithCounter() {
        return List.of("token_number", "service_group_name", "service_name", "counter_label", "site_name");
    }

    private static List<String> appointmentVars() {
        return List.of("token_number", "service_name", "site_name", "date", "time");
    }

    private static List<String> staffAlertVars() {
        return List.of("service_group_name", "counter_label", "site_name");
    }

    private final String key;
    private final List<String> defaultChannelOrder;
    private final boolean defaultEnabled;
    private final boolean essential;
    private final Set<String> variables;

    NotificationTriggerKey(String key, List<String> defaultChannelOrder, boolean defaultEnabled, boolean essential, List<String> variables) {
        this.key = key;
        this.defaultChannelOrder = defaultChannelOrder;
        this.defaultEnabled = defaultEnabled;
        this.essential = essential;
        this.variables = Set.copyOf(variables);
    }

    public String key() {
        return key;
    }

    public List<String> defaultChannelOrder() {
        return defaultChannelOrder;
    }

    /** Whether the trigger bypasses quiet hours (FR-NTF-031) and cannot be opted out of (FR-NTF-035). */
    public boolean essential() {
        return essential;
    }

    public Set<String> variables() {
        return variables;
    }

    /**
     * The default state before any site or service override (§14.2). {@code ticket_issued} is the one trigger whose
     * table default depends on how the ticket was issued: "On for remote joins, off for kiosk" — {@code originChannel}
     * is the ticket's {@code origin_channel} ({@code mobile} is the only remote-join value; null when the trigger has
     * no ticket at all, which never applies to {@code ticket_issued}).
     */
    public boolean defaultEnabled(String originChannel) {
        if (this == TICKET_ISSUED) return "mobile".equals(originChannel);
        return defaultEnabled;
    }

    public static Optional<NotificationTriggerKey> fromKey(String key) {
        return Arrays.stream(values()).filter(t -> t.key.equals(key)).findFirst();
    }
}
