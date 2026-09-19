package com.qms.session;

import com.qms.session.SessionRepository.BoundTicket;
import com.qms.session.SessionRepository.CounterRow;
import com.qms.session.SessionRepository.OutcomeRow;
import com.qms.session.SessionRepository.ServiceLink;
import java.util.List;

/** Turns stored rows into the shapes the API returns. */
final class SessionViews {

    private SessionViews() {}

    static SessionResponse.CounterRef counter(CounterRow c) {
        return new SessionResponse.CounterRef(c.id(), c.label(), c.zoneId(), c.zoneName(), c.siteId());
    }

    static SessionResponse.ServiceRef service(ServiceLink s) {
        return new SessionResponse.ServiceRef(s.serviceId(), s.names(), s.weight());
    }

    static SessionResponse.SessionTicket ticket(BoundTicket t, List<OutcomeRow> outcomes, int announceLimit, int missLimit, boolean callTimedOut) {
        return new SessionResponse.SessionTicket(
                t.id(),
                t.tokenNumber(),
                t.state(),
                t.version(),
                new SessionResponse.Named(t.serviceId(), t.serviceNames()),
                t.originChannel(),
                t.priorityClassId() == null ? null : new SessionResponse.Named(t.priorityClassId(), t.priorityClassNames()),
                t.queuedAt(),
                t.calledAt(),
                t.servedAt(),
                com.qms.queue.TicketTimings.seconds(t.queuedAt(), t.calledAt()),
                t.announceCount(),
                announceLimit,
                t.missCount(),
                missLimit,
                callTimedOut,
                outcomes.stream().map(o -> new SessionResponse.Outcome(o.id(), o.code(), o.labels())).toList());
    }
}
