package com.qms.platform.notifications;

/**
 * The trigger keys of SRS §14.2 that a queue transition or an appointment lifecycle change maps onto (tickets 38,
 * 40), shared as plain strings so {@code com.qms.queue.TicketEvents} and {@code com.qms.appointment.*} can name a
 * trigger without depending on the {@code com.qms.notification} package (ArchitectureTest's package-cycle rule;
 * {@code com.qms.platform} itself may not depend on any bounded context either). The full catalogue — including
 * triggers no caller fires yet, such as {@code approaching_turn} and the operational-alert ones — lives in
 * {@code com.qms.notification.NotificationTriggerKey}, whose entries for these same triggers use these same literal
 * values.
 */
public final class NotificationTriggerKeys {

    public static final String TICKET_ISSUED = "ticket_issued";
    public static final String APPROACHING_TURN = "approaching_turn";
    public static final String YOUR_TURN = "your_turn";
    public static final String MISSED_BACK_IN_QUEUE = "missed_back_in_queue";
    public static final String MARKED_NO_SHOW = "marked_no_show";
    public static final String TICKET_TRANSFERRED = "ticket_transferred";
    public static final String TICKET_FORFEITED = "ticket_forfeited";
    public static final String SERVICE_COMPLETED_FEEDBACK = "service_completed_feedback";
    public static final String APPOINTMENT_CONFIRMED = "appointment_confirmed";
    public static final String APPOINTMENT_REMINDER = "appointment_reminder";
    public static final String APPOINTMENT_RESCHEDULED_OR_CANCELLED = "appointment_rescheduled_or_cancelled";
    public static final String WAITLIST_SLOT_OFFERED = "waitlist_slot_offered";

    private NotificationTriggerKeys() {}
}
