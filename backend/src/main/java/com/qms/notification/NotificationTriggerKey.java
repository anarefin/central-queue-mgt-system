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
 * <p>{@code approaching_turn} and the operational-alert triggers still have no caller wired (they need wait-estimate
 * thresholds and live-dashboard monitoring that later tickets build — 46-47 for the operational alerts) but are
 * catalogued here with their defaults and variables so their templates and per-site/service settings can already be
 * configured (FR-NTF-010, FR-NTF-020). The appointment triggers (ticket 40, FR-APT-050) are fired from
 * {@code com.qms.appointment.AppointmentBookingService} and its reminder scheduler.
 */
public enum NotificationTriggerKey {
    TICKET_ISSUED(NotificationTriggerKeys.TICKET_ISSUED, List.of("web_push", "in_app"), true, false, ticketVars()),
    APPROACHING_TURN("approaching_turn", List.of("web_push", "in_app"), true, false, ticketVars()),
    YOUR_TURN(NotificationTriggerKeys.YOUR_TURN, List.of("web_push", "in_app"), true, true, ticketVarsWithCounter()),
    MISSED_BACK_IN_QUEUE(NotificationTriggerKeys.MISSED_BACK_IN_QUEUE, List.of("web_push", "in_app"), true, true, ticketVars()),
    MARKED_NO_SHOW(NotificationTriggerKeys.MARKED_NO_SHOW, List.of("web_push", "in_app"), true, false, ticketVars()),
    TICKET_TRANSFERRED(NotificationTriggerKeys.TICKET_TRANSFERRED, List.of("web_push", "in_app"), true, false, ticketVars()),
    APPOINTMENT_CONFIRMED(NotificationTriggerKeys.APPOINTMENT_CONFIRMED, List.of("email", "web_push"), true, false, appointmentVars()),
    APPOINTMENT_REMINDER(NotificationTriggerKeys.APPOINTMENT_REMINDER, List.of("email", "web_push"), true, false, appointmentVars()),
    APPOINTMENT_RESCHEDULED_OR_CANCELLED(NotificationTriggerKeys.APPOINTMENT_RESCHEDULED_OR_CANCELLED, List.of("email", "web_push"), true, false, appointmentVars()),
    WAITLIST_SLOT_OFFERED(NotificationTriggerKeys.WAITLIST_SLOT_OFFERED, List.of("web_push", "email"), true, true, appointmentVars()),
    SERVICE_COMPLETED_FEEDBACK(NotificationTriggerKeys.SERVICE_COMPLETED_FEEDBACK, List.of("web_push", "in_app"), false, false, ticketVars()),
    QUEUE_SLA_BREACH("queue_sla_breach", List.of("staff_alert"), true, true, staffAlertVars()),
    AGENT_BREAK_OVERRUN("agent_break_overrun", List.of("staff_alert"), true, true, staffAlertVars()),
    COUNTER_UNATTENDED("counter_unattended", List.of("staff_alert"), true, true, staffAlertVars()),
    KIOSK_DISPLAY_OFFLINE("kiosk_display_offline", List.of("staff_alert"), true, true, staffAlertVars());

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
