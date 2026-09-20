package com.qms.platform.notifications;

/**
 * The one seam a bounded context fires a notification trigger through (SRS §14, ticket 38): a trigger key from the
 * catalogue (§14.2, see {@link NotificationTriggerKeys}) plus the context to render and route it. Implemented once,
 * by {@code com.qms.notification.NotificationDispatcher}; a caller such as {@code com.qms.queue.TicketEvents} never
 * learns which channels exist or how retries and fallback work — the same separation {@code RealtimePublisher}
 * already gives realtime delivery. Firing is a single fast, transactional insert (FR-NTF-003): the actual send
 * happens later, off the caller's thread, in the job worker.
 */
public interface NotificationTrigger {

    void fire(String triggerKey, NotificationContext context);
}
