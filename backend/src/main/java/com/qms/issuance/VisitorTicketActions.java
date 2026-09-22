package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.TicketRepository.TicketRecord;
import com.qms.notification.NotificationConsentService;
import com.qms.notification.NotificationProperties;
import com.qms.notification.WebPushSubscriptionService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.queue.QueueProperties;
import com.qms.queue.QueueReads;
import com.qms.queue.TicketEvents;
import com.qms.queue.TicketTransition;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A visitor's own cancel of their own ticket (FR-MOB-030, ticket 37): allowed only before it is called, a narrower rule
 * than staff's own cancel (SRS §19.1, {@code com.qms.session.TicketActions#cancel}, "any active state"), which this does
 * not reuse: {@code com.qms.session} already depends on {@code com.qms.issuance} (it reads {@code Channels} and
 * {@code JourneyService}), so the reverse dependency would be a package cycle (ArchitectureTest). A visitor never binds a
 * Counter session, so there is nothing here to free or unpause beyond what {@link TicketEvents#append} already does for
 * every transition (the Journey pause/unpause reaction, FR-QUE-063).
 */
@Service
@Profile(Profiles.SERVING)
class VisitorTicketActions {

    static final String CANCELLED_EVENT = "ticket.cancelled";
    /** Cancellable states for a visitor: never {@code called}, {@code serving} or {@code held} (FR-MOB-030). */
    private static final List<String> CANCELLABLE = List.of("remote", "waiting", "paused");
    private static final Set<String> CANCELLABLE_SET = Set.copyOf(CANCELLABLE);

    private final TicketRepository tickets;
    private final TicketEvents events;
    private final AuditWriter audit;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final NotificationConsentService consent;
    private final RetentionConsentService retentionConsent;
    private final NotificationProperties notificationProperties;
    private final WebPushSubscriptionService webPush;
    private final QueueReads queues;
    private final QueueProperties queueProperties;

    VisitorTicketActions(
            TicketRepository tickets,
            TicketEvents events,
            AuditWriter audit,
            Clock clock,
            JdbcTemplate jdbc,
            NotificationConsentService consent,
            RetentionConsentService retentionConsent,
            NotificationProperties notificationProperties,
            WebPushSubscriptionService webPush,
            QueueReads queues,
            QueueProperties queueProperties) {
        this.tickets = tickets;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
        this.consent = consent;
        this.retentionConsent = retentionConsent;
        this.notificationProperties = notificationProperties;
        this.webPush = webPush;
        this.queues = queues;
        this.queueProperties = queueProperties;
    }

    /**
     * The visitor's own opt-out of non-essential notifications (FR-NTF-035, FR-SEC-030, ticket 38): recorded against
     * their visitor record, so it outlives this one ticket, with a timestamp and the version of the consent text
     * they saw. A ticket with no visitor record (an anonymous walk-in) has nothing to opt out on.
     */
    @Transactional
    Map<String, Object> setNotificationOptOut(TicketRecord ticket, boolean optedOut, String consentTextVersion) {
        UUID visitorId = jdbc.query("SELECT visitor_id FROM ticket WHERE id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ticket.id());
        if (visitorId == null) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "no_visitor_record"));
        String version = consentTextVersion == null || consentTextVersion.isBlank() ? notificationProperties.consentTextVersion() : consentTextVersion;
        consent.setOptOut(visitorId, optedOut, version, clock.instant());
        audit.record(AuditEvent.of("notification.consent", "visitor", visitorId)
                .withAfter(Map.of("opted_out", optedOut, "consent_text_version", version))
                .withReason("visitor_ticket_page"));
        return Map.of("opted_out", optedOut);
    }

    /** The default consent-text version when the caller sends none, the same fallback shape {@link
     * NotificationProperties#consentTextVersion()} already gives notification consent. */
    private static final String DEFAULT_RETENTION_CONSENT_VERSION = "v1";

    /**
     * The visitor's own consent for retention (FR-SEC-030, ticket 54): recorded against their visitor record with a
     * timestamp and the version of the consent text they saw, the same shape {@link #setNotificationOptOut} already
     * gives consent for notifications. A ticket with no visitor record has nothing to record consent on.
     */
    @Transactional
    Map<String, Object> setRetentionConsent(TicketRecord ticket, boolean granted, String consentTextVersion) {
        UUID visitorId = jdbc.query("SELECT visitor_id FROM ticket WHERE id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ticket.id());
        if (visitorId == null) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "no_visitor_record"));
        String version = consentTextVersion == null || consentTextVersion.isBlank() ? DEFAULT_RETENTION_CONSENT_VERSION : consentTextVersion;
        retentionConsent.setConsent(visitorId, granted, version, clock.instant());
        audit.record(AuditEvent.of("retention.consent", "visitor", visitorId)
                .withAfter(Map.of("granted", granted, "consent_text_version", version))
                .withReason("visitor_ticket_page"));
        return Map.of("granted", granted);
    }

    /**
     * A visitor's own device opts into Web Push for this ticket (ticket 39, §18.3, FR-INT-040): the browser's own
     * {@code PushSubscription}, stored keyed on its own endpoint so a re-subscribe upserts rather than duplicating.
     * A ticket with no visitor record still has a subscription worth keeping — Web Push targets the device that
     * asked, not the visitor identity opt-out does ({@link #setNotificationOptOut}).
     */
    @Transactional
    Map<String, Object> subscribeWebPush(TicketRecord ticket, String endpoint, String p256dh, String auth) {
        UUID visitorId = jdbc.query("SELECT visitor_id FROM ticket WHERE id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ticket.id());
        webPush.subscribe(ticket.id(), visitorId, endpoint, p256dh, auth);
        audit.record(AuditEvent.of("notification.push_subscribed", "ticket", ticket.id()).withReason("visitor_ticket_page"));
        return Map.of("subscribed", true);
    }

    @Transactional
    TicketRecord cancel(TicketRecord ticket) {
        if (!CANCELLABLE_SET.contains(ticket.state())) throw refusal();
        if (!tickets.cancelIfIn(ticket.id(), CANCELLABLE)) throw refusal();

        Instant now = clock.instant();
        events.append(new TicketEvents.Transition(
                ticket.id(), CANCELLED_EVENT, ticket.state(), TicketTransition.CANCELLED, null, ActorType.VISITOR.wire(), null, null, now, now));
        audit.record(AuditEvent.of(CANCELLED_EVENT, "ticket", ticket.id())
                .withBefore(Map.of("state", ticket.state()))
                .withAfter(Map.of("state", TicketTransition.CANCELLED))
                .withReason("visitor_cancel"));
        return tickets.ticket(ticket.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static ApiException refusal() {
        return new ApiException(ErrorCode.CONFLICT, Map.of("reason", "ticket_already_called"));
    }

    /** A Site's geofence, if an admin has set one (ticket 43, FR-MOB-024): null radius means the geofence leg is off. */
    private record Geofence(double latitude, double longitude, Integer radiusMeters) {}

    /**
     * A remote ticket is marked present (FR-MOB-021, §19.1 {@code remote -> waiting}): by a site QR (a drift fallback
     * that skips the distance check, FR-MOB-024) or by tapping check-in inside the Site's geofence (validated here
     * against {@code site_location}). Nothing about the ticket's Score changes: it joins the callable queue exactly
     * where it already ranked (FR-MOB-012).
     */
    @Transactional
    TicketRecord checkIn(TicketRecord ticket, String method, Double latitude, Double longitude) {
        if (!"remote".equals(ticket.state())) throw checkInRefusal("ticket_not_remote");
        if (!"qr".equals(method) && !"geofence".equals(method)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "method", "code", "invalid"))));
        }
        if ("geofence".equals(method)) {
            if (latitude == null || longitude == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "latitude", "code", "required"))));
            }
            Geofence geofence = jdbc.query(
                            "SELECT latitude, longitude, geofence_radius_m FROM site_location WHERE site_id = ?",
                            (rs, i) -> new Geofence(rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getObject("geofence_radius_m", Integer.class)),
                            ticket.siteId())
                    .stream().findFirst().orElse(null);
            if (geofence == null || geofence.radiusMeters() == null) throw checkInRefusal("geofence_not_configured");
            double meters = GeoDistance.metersBetween(geofence.latitude(), geofence.longitude(), latitude, longitude);
            if (meters > geofence.radiusMeters()) {
                throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "too_far", "max_distance_m", geofence.radiusMeters(), "distance_m", Math.round(meters)));
            }
        }
        if (!tickets.checkIn(ticket.id())) throw checkInRefusal("ticket_not_remote");

        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        events.append(new TicketEvents.Transition(
                ticket.id(), TicketTransition.CHECK_IN.eventType(), TicketTransition.CHECK_IN.from(), TicketTransition.CHECK_IN.to(),
                null, ActorType.VISITOR.wire(), null, payload, now, now));
        audit.record(AuditEvent.of(TicketTransition.CHECK_IN.eventType(), "ticket", ticket.id())
                .withBefore(Map.of("state", "remote"))
                .withAfter(Map.of("state", TicketTransition.CHECK_IN.to()))
                .withReason("visitor_check_in:" + method));
        return tickets.ticket(ticket.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static ApiException checkInRefusal(String reason) {
        return new ApiException(ErrorCode.CONFLICT, Map.of("reason", reason));
    }

    /**
     * A visitor's own "not ready yet" (FR-MOB-031), once per ticket while it is still remote, if the Service allows it:
     * moves the ticket back the configured places (default 3) as a Score adjustment (ADR-0004); {@code queued_at} is
     * never rewritten.
     */
    @Transactional
    TicketRecord delay(TicketRecord ticket) {
        if (!"remote".equals(ticket.state())) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "ticket_not_remote"));
        boolean delayAllowed = Boolean.TRUE.equals(jdbc.query(
                        "SELECT delay_allowed FROM service_remote_rule WHERE service_id = ?", (rs, i) -> rs.getBoolean("delay_allowed"), ticket.serviceId())
                .stream().findFirst().orElse(false));
        if (!delayAllowed) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "delay_not_allowed"));

        int adjustment = queues.moveBackAdjustment(ticket.id(), queueProperties.remoteDelayBackPlaces());
        if (!tickets.delay(ticket.id(), adjustment)) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "delay_already_used"));

        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("score_adjustment_minutes", adjustment);
        events.append(new TicketEvents.Transition(
                ticket.id(), TicketTransition.DELAY.eventType(), TicketTransition.DELAY.from(), TicketTransition.DELAY.to(),
                null, ActorType.VISITOR.wire(), null, payload, now, now));
        audit.record(AuditEvent.of(TicketTransition.DELAY.eventType(), "ticket", ticket.id())
                .withBefore(Map.of("state", "remote"))
                .withAfter(Map.of("score_adjustment_minutes", adjustment))
                .withReason("visitor_delay"));
        return tickets.ticket(ticket.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
