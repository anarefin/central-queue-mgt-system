package com.qms.platform.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * What a bounded context hands the notification pipeline when a trigger fires (SRS §14, ticket 38): enough to look
 * up everything else (visitor, site, service, counter) without the pipeline depending on the caller's own tables.
 * {@code ticketId}, {@code visitorId} and {@code counterId} are null where the trigger has none of those, such as an
 * operational alert with no ticket at all.
 *
 * <p>{@code date} and {@code time} (ticket 40, FR-APT-050) are the one pair of values the pipeline cannot otherwise
 * look up: an appointment's own slot, already formatted ({@code yyyy-MM-dd}, {@code HH:mm}, the same shapes {@code
 * AppointmentResponse} itself uses) since the pipeline has no appointment table to resolve them from. Null for every
 * queue-side trigger, which carries no slot at all.
 */
public record NotificationContext(
        UUID siteId, UUID serviceId, UUID ticketId, UUID visitorId, UUID counterId, String tokenNumber, Instant occurredAt,
        String date, String time) {}
