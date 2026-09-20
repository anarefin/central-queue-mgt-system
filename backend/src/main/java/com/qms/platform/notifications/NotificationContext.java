package com.qms.platform.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * What a bounded context hands the notification pipeline when a trigger fires (SRS §14, ticket 38): enough to look
 * up everything else (visitor, site, service, counter) without the pipeline depending on the caller's own tables.
 * {@code ticketId}, {@code visitorId} and {@code counterId} are null where the trigger has none of those, such as an
 * operational alert with no ticket at all.
 */
public record NotificationContext(
        UUID siteId, UUID serviceId, UUID ticketId, UUID visitorId, UUID counterId, String tokenNumber, Instant occurredAt) {}
