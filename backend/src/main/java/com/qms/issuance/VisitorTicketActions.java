package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.TicketRepository.TicketRecord;
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
import org.springframework.context.annotation.Profile;
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

    VisitorTicketActions(TicketRepository tickets, TicketEvents events, AuditWriter audit, Clock clock) {
        this.tickets = tickets;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
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
