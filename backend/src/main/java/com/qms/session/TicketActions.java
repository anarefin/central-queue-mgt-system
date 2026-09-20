package com.qms.session;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.Authz;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Permission;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueReads;
import com.qms.queue.TicketEvents;
import com.qms.queue.TicketTimings;
import com.qms.queue.TicketTransition;
import com.qms.session.SessionRepository.ActionTicket;
import com.qms.session.SessionRepository.ClassRow;
import com.qms.session.SessionRepository.SessionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What staff do to a ticket that is not an agent's serving action: change a waiting ticket's Priority class (FR-QUE-012, UAT U9)
 * and cancel an active ticket (§5.2, §19.1). Both check the permission and the caller's site and Service group on the server
 * (FR-CFG-103, FR-CFG-106), lock the ticket, change it on its version, write exactly one ticket event (Invariant 3) and an audit
 * entry. This lives with the counter session because a cancel may take a ticket out of one.
 *
 * <p>Order is computed, never stored (ADR-0004), so a class that has changed is in effect at the next read of the queue; the event
 * tells every console and display watching the queue to read it again.
 */
@Service
@Profile(Profiles.SERVING)
public class TicketActions {

    static final String REPRIORITISE = "hasAuthority(T(com.qms.platform.security.Authorities).TICKET_REPRIORITISE)";
    /** Everyone with the permission, an agent for their own tickets only (§5.2); the service checks which are theirs. */
    static final String CANCEL = "hasAnyAuthority(T(com.qms.platform.security.Authorities).TICKET_CANCEL,"
            + " T(com.qms.platform.security.Authorities).TICKET_CANCEL + ':own')";
    static final String CHECKIN = "hasAuthority(T(com.qms.platform.security.Authorities).TICKET_CHECKIN)";

    static final String PRIORITY_EVENT = "ticket.position_changed";
    static final String PRIORITY_AUDIT = "ticket.priority_changed";
    private static final String STAFF_CHECK_IN_METHOD = "reception";

    private static final String STAFF = "staff";
    private static final int MAX_REASON_LENGTH = 1000;

    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final QueueReads queues;
    private final TicketEvents events;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Authz authz;
    private final CurrentUser currentUser;
    private final Clock clock;

    TicketActions(
            SessionRepository sessions,
            SessionService sessionService,
            QueueReads queues,
            TicketEvents events,
            AuditWriter audit,
            ScopeGuard scope,
            Authz authz,
            CurrentUser currentUser,
            Clock clock) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.queues = queues;
        this.events = events;
        this.audit = audit;
        this.scope = scope;
        this.authz = authz;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Gives a waiting ticket another Priority class, with a mandatory reason recorded in the audit log (FR-QUE-012, FR-SEC-040).
     * The ticket keeps its wait and its place in the arithmetic of the score: only its Head start (and maximum wait) change, so
     * it moves ahead of or behind the tickets whose scores it now passes.
     */
    @PreAuthorize(REPRIORITISE)
    @Transactional
    public TicketChange reprioritise(UUID ticketId, ReprioritiseRequest request, Integer ifMatch) {
        ActionTicket ticket = sessions.actionTicket(ticketId, false).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(ticket.siteId());
        scope.requireGroup(ticket.groupId());

        if (request == null || request.priorityClassId() == null) throw invalid("priority_class_id", "required");
        String reason = reason(request.reason(), true);
        ClassRow target = sessions.priorityClass(request.priorityClassId()).orElseThrow(() -> invalid("priority_class_id", "not_found"));
        if (!target.active()) throw invalid("priority_class_id", "inactive");

        ticket = sessions.actionTicket(ticketId, true).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!TicketTransition.mayReprioritise(ticket.state())) throw refusal("ticket_not_waiting");
        if (ifMatch != null && ifMatch != ticket.version()) throw refusal("version_mismatch");
        UUID defaultClass = sessions.defaultClassId();
        UUID before = ticket.priorityClassId() == null ? defaultClass : ticket.priorityClassId();
        if (before.equals(target.id())) throw refusal("same_priority_class");

        // The default class is stored as no class at all, as it is at issue.
        UUID stored = target.isDefault() ? null : target.id();
        if (!sessions.reprioritise(ticket.id(), ticket.version(), stored)) throw refusal("version_mismatch");

        Instant now = clock.instant();
        UUID actor = currentUser.require().userId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("priority_class_id", target.id().toString());
        payload.put("previous_priority_class_id", before.toString());
        payload.put("manual", true);
        events.append(new TicketEvents.Transition(ticket.id(), PRIORITY_EVENT, ticket.state(), ticket.state(), actor, STAFF, null, payload, now, now));
        audit.record(AuditEvent.of(PRIORITY_AUDIT, "ticket", ticket.id())
                .withBefore(classSnapshot(ticket, before))
                .withAfter(classSnapshot(ticket, target.id()))
                .withReason(reason));
        return change(ticket.id());
    }

    /**
     * Reception marks a remote ticket present on the visitor's behalf (ticket 43, FR-MOB-021, §19.1
     * {@code remote -> waiting}): the same move the visitor's own QR scan or geofence check-in makes. Nothing about
     * the ticket's Score changes — it joins the callable queue exactly where it already ranked (FR-MOB-012).
     */
    @PreAuthorize(CHECKIN)
    @Transactional
    public TicketChange receptionCheckIn(UUID ticketId, Integer ifMatch) {
        UUID user = currentUser.require().userId();
        ActionTicket ticket = sessions.actionTicket(ticketId, true).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(ticket.siteId());
        scope.requireGroup(ticket.groupId());
        if (!"remote".equals(ticket.state())) throw refusal("ticket_not_remote");
        if (ifMatch != null && ifMatch != ticket.version()) throw refusal("version_mismatch");
        if (!sessions.checkIn(ticket.id(), ticket.version())) throw refusal("version_mismatch");

        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", STAFF_CHECK_IN_METHOD);
        events.append(new TicketEvents.Transition(
                ticket.id(), TicketTransition.CHECK_IN.eventType(), TicketTransition.CHECK_IN.from(), TicketTransition.CHECK_IN.to(), user, STAFF, null, payload, now, now));
        audit.record(AuditEvent.of(TicketTransition.CHECK_IN.eventType(), "ticket", ticket.id())
                .withBefore(Map.of("state", "remote"))
                .withAfter(Map.of("state", TicketTransition.CHECK_IN.to()))
                .withReason("reception_check_in"));
        return change(ticket.id());
    }

    /**
     * Cancels an active ticket (SRS §19.1: any active state to {@code cancelled}, by staff). Everyone with the permission may cancel any
     * ticket in their site and Service group; an agent only their own, which is a ticket their session has called, is serving
     * or holds, or one meant for them. The Session binding is cleared with the terminal state (Invariant 2) and a session that was
     * closing on this ticket closes now (§19.3). The reason is optional.
     */
    @PreAuthorize(CANCEL)
    @Transactional
    public TicketChange cancel(UUID ticketId, CancelTicketRequest request, Integer ifMatch) {
        UUID user = currentUser.require().userId();
        ActionTicket seen = sessions.actionTicket(ticketId, false).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(seen.siteId());
        scope.requireGroup(seen.groupId());
        String reason = reason(request == null ? null : request.reason(), false);

        // The session is locked first, like every other action on it, then the ticket under the lock (a Session then a ticket).
        SessionRow session = seen.sessionId() == null ? null : sessions.lock(seen.sessionId()).orElse(null);
        ActionTicket ticket = sessions.actionTicket(ticketId, true).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // A call landed between the read and the lock: the session would have to be locked after the ticket. Start again.
        if (!Objects.equals(seen.sessionId(), ticket.sessionId())) throw refusal("version_mismatch");
        if (!authz.has(Permission.TICKET_CANCEL) && !isOwn(ticket, session, user)) throw new ApiException(ErrorCode.FORBIDDEN);
        if (TicketTransition.cancel(ticket.state()).isEmpty()) throw refusal("ticket_not_active");
        if (ifMatch != null && ifMatch != ticket.version()) throw refusal("version_mismatch");

        Instant now = clock.instant();
        // Only time in waiting counts as wait (Invariant 1): count the stint the cancel itself ends.
        List<TicketTimings.Change> changes = new ArrayList<>(events.changes(ticket.id()));
        changes.add(new TicketTimings.Change(now, ticket.state(), TicketTransition.CANCELLED));
        int waitSeconds = TicketTimings.accruedWait(ticket.queuedAt(), changes);
        Integer serviceSeconds = ticket.servedAt() == null ? null : TicketTimings.seconds(ticket.servedAt(), now);
        if (!sessions.cancel(ticket.id(), ticket.version(), now, waitSeconds, serviceSeconds)) throw refusal("version_mismatch");

        Map<String, Object> payload = new LinkedHashMap<>();
        if (session != null) payload.put("session_id", session.id().toString());
        payload.put("wait_seconds", waitSeconds);
        if (serviceSeconds != null) payload.put("service_seconds", serviceSeconds);
        // The counter is named only while the ticket is in its hands, so that counter's console hears of it.
        UUID counterId = session == null ? null : ticket.counterId();
        events.append(new TicketEvents.Transition(ticket.id(), TicketTransition.CANCELLED_EVENT, ticket.state(), TicketTransition.CANCELLED, user, STAFF, counterId, payload, now, now));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("token_number", ticket.tokenNumber());
        after.put("state", TicketTransition.CANCELLED);
        audit.record(AuditEvent.of(TicketTransition.CANCELLED_EVENT, "ticket", ticket.id())
                .withBefore(Map.of("token_number", ticket.tokenNumber(), "state", ticket.state()))
                .withAfter(after)
                .withReason(reason));

        if (session != null) sessionService.ticketLeft(sessions.session(session.id()).orElseThrow(), now);
        return change(ticket.id());
    }

    /** Own records only (§5.2): a ticket the caller's session has called, is serving or holds, or one meant for them. */
    private static boolean isOwn(ActionTicket ticket, SessionRow session, UUID user) {
        if (session != null && session.agentId().equals(user)) return true;
        return session == null && user.equals(ticket.targetAgentId());
    }

    private TicketChange change(UUID ticketId) {
        ActionTicket ticket = sessions.actionTicket(ticketId, false).orElseThrow();
        UUID effective = ticket.priorityClassId() == null ? sessions.defaultClassId() : ticket.priorityClassId();
        return new TicketChange(ticket.id(), ticket.tokenNumber(), ticket.state(), effective, queues.positionOf(ticket.id()), ticket.version());
    }

    private static Map<String, Object> classSnapshot(ActionTicket ticket, UUID classId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("token_number", ticket.tokenNumber());
        values.put("priority_class_id", classId.toString());
        return values;
    }

    private static String reason(String given, boolean required) {
        String reason = given == null || given.isBlank() ? null : given.strip();
        if (reason == null && required) throw invalid("reason", "required");
        if (reason != null && reason.length() > MAX_REASON_LENGTH) throw invalid("reason", "too_long");
        return reason;
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    private static ApiException refusal(String reason) {
        return new ApiException(ErrorCode.CONFLICT, Map.of("reason", reason));
    }
}
