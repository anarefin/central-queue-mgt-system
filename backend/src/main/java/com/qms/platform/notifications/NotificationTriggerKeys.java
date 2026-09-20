package com.qms.platform.notifications;

/**
 * The trigger keys of SRS §14.2 that a queue transition maps onto (ticket 38), shared as plain strings so
 * {@code com.qms.queue.TicketEvents} can name a trigger without depending on the {@code com.qms.notification}
 * package (ArchitectureTest's package-cycle rule; {@code com.qms.platform} itself may not depend on any bounded
 * context either). The full catalogue — including triggers no caller fires yet, such as the appointment and
 * operational-alert ones — lives in {@code com.qms.notification.NotificationTriggerKey}, whose entries for these
 * same triggers use these same literal values.
 */
public final class NotificationTriggerKeys {

    public static final String TICKET_ISSUED = "ticket_issued";
    public static final String YOUR_TURN = "your_turn";
    public static final String MISSED_BACK_IN_QUEUE = "missed_back_in_queue";
    public static final String MARKED_NO_SHOW = "marked_no_show";
    public static final String TICKET_TRANSFERRED = "ticket_transferred";
    public static final String SERVICE_COMPLETED_FEEDBACK = "service_completed_feedback";

    private NotificationTriggerKeys() {}
}
