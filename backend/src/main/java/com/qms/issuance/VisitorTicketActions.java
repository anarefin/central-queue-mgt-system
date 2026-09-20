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
import com.qms.queue.TicketEvents;
import com.qms.queue.TicketTransition;
import java.time.Clock;
import java.time.Instant;
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
    private final NotificationProperties notificationProperties;
    private final WebPushSubscriptionService webPush;

    VisitorTicketActions(
            TicketRepository tickets,
            TicketEvents events,
            AuditWriter audit,
            Clock clock,
            JdbcTemplate jdbc,
            NotificationConsentService consent,
            NotificationProperties notificationProperties,
            WebPushSubscriptionService webPush) {
        this.tickets = tickets;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
        this.consent = consent;
        this.notificationProperties = notificationProperties;
        this.webPush = webPush;
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
}
