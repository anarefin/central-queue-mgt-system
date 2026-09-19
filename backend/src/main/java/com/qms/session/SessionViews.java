package com.qms.session;

import com.qms.session.SessionRepository.BoundTicket;
import com.qms.session.SessionRepository.CounterRow;
import com.qms.session.SessionRepository.OutcomeRow;
import com.qms.session.SessionRepository.ServiceLink;
import com.qms.issuance.Channels;
import java.util.List;
import java.util.Set;

/** Turns stored rows into the shapes the API returns. */
final class SessionViews {

    private SessionViews() {}

    static SessionResponse.CounterRef counter(CounterRow c) {
        return new SessionResponse.CounterRef(c.id(), c.label(), c.zoneId(), c.zoneName(), c.siteId());
    }

    static SessionResponse.ServiceRef service(ServiceLink s) {
        return new SessionResponse.ServiceRef(s.serviceId(), s.names(), s.weight());
    }

    static SessionResponse.SessionTicket ticket(
            BoundTicket t, List<OutcomeRow> outcomes, int announceLimit, int missLimit, boolean callTimedOut, Set<VisitorField> visible) {
        return new SessionResponse.SessionTicket(
                t.id(),
                t.tokenNumber(),
                t.state(),
                t.version(),
                new SessionResponse.Named(t.serviceId(), t.serviceNames()),
                t.originChannel(),
                Channels.APPOINTMENT_CHECKIN.equals(t.originChannel()),
                visitor(t, visible),
                visible.contains(VisitorField.PURPOSE_NOTE) ? t.purposeNote() : null,
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

    /** The visitor fields of the ticket that the caller may see, or null when there are none to show. */
    private static SessionResponse.VisitorView visitor(BoundTicket t, Set<VisitorField> visible) {
        String code = visible.contains(VisitorField.CODE) ? t.visitorCode() : null;
        String name = visible.contains(VisitorField.NAME) ? t.visitorName() : null;
        String category = visible.contains(VisitorField.CATEGORY) ? t.visitorCategory() : null;
        return code == null && name == null && category == null ? null : new SessionResponse.VisitorView(code, name, category);
    }
}
