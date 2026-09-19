package com.qms.session;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import com.qms.platform.security.CurrentUser;
import com.qms.queue.QueueEngine;
import com.qms.queue.QueueEngine.Contender;
import com.qms.queue.QueueProperties;
import com.qms.queue.QueueReads;
import com.qms.queue.TicketEvents;
import com.qms.queue.TicketTimings;
import com.qms.queue.TicketTransition;
import com.qms.session.SessionRepository.BoundTicket;
import com.qms.session.SessionRepository.CounterRow;
import com.qms.session.SessionRepository.OutcomeRow;
import com.qms.session.SessionRepository.ServiceLink;
import com.qms.session.SessionRepository.SessionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An Agent's counter session and the serving actions that run inside it (SRS §11, §19.1, §19.3). The session is the
 * unit routing sees (CONTEXT.md): a called ticket is bound to it (ADR-0008), and every action here is checked against
 * that binding, on the server, whatever the console shows (FR-CFG-103, FR-CFG-105).
 *
 * <p>Every action on a session first locks its row, so one session's actions never interleave (a double press of F2 calls
 * one ticket, not two). Two sessions calling at once are kept apart by the ticket itself: a call is a compare-and-set on
 * the ticket's version, so one loses and picks again (FR-QUE-031). Each transition writes exactly one ticket event in the
 * same transaction (Invariant 3).
 */
@Service
@Profile(Profiles.SERVING)
public class SessionService {

    static final String OPEN_CLOSE = "hasAnyAuthority(T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE,"
            + " T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE + ':own')";
    static final String SERVE = "hasAnyAuthority(T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE,"
            + " T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE + ':own')";
    static final String EITHER = "hasAnyAuthority(T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE,"
            + " T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE + ':own',"
            + " T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE,"
            + " T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE + ':own')";

    private static final String STAFF = "staff";
    private static final String AGENT_INDEX = "counter_session_live_agent_uq";
    /** How often a call retries after losing a ticket to another counter before it reports nothing is waiting. */
    private static final int CALL_ATTEMPTS = 5;
    private static final int MAX_NOTE_LENGTH = 1000;

    private final SessionRepository sessions;
    private final QueueReads queues;
    private final TicketEvents events;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final CurrentUser currentUser;
    private final QueueProperties queueProperties;
    private final Clock clock;

    SessionService(
            SessionRepository sessions,
            QueueReads queues,
            TicketEvents events,
            AuditWriter audit,
            ScopeGuard scope,
            CurrentUser currentUser,
            QueueProperties queueProperties,
            Clock clock) {
        this.sessions = sessions;
        this.queues = queues;
        this.events = events;
        this.audit = audit;
        this.scope = scope;
        this.currentUser = currentUser;
        this.queueProperties = queueProperties;
        this.clock = clock;
    }

    // ---- opening and restoring --------------------------------------------------------------------------------

    /** The counters the caller may occupy, in the sites their token allows, each with the Services it would let them serve. */
    @PreAuthorize(OPEN_CLOSE)
    @Transactional(readOnly = true)
    public CounterOptions options() {
        UUID user = currentUser.require().userId();
        var allowed = scope.sites(List.of());
        List<CounterOptions.Item> items = sessions.permittedCounters(user).stream()
                .filter(item -> allowed.isEmpty() || allowed.contains(item.counter().siteId()))
                .toList();
        return new CounterOptions(items);
    }

    /**
     * Opens a session on a counter the caller is permitted to occupy (FR-AGT-001) for the Services they choose, else all
     * of them (FR-AGT-003). The database allows one live session per counter (§18.4); the checks here only give the
     * refusals their names.
     */
    @PreAuthorize(OPEN_CLOSE)
    @Transactional
    public SessionResponse open(OpenSessionRequest request) {
        UUID user = currentUser.require().userId();
        if (request == null || request.counterId() == null) throw invalid("counter_id", "required");
        CounterRow counter = sessions.counter(request.counterId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(counter.siteId());
        List<ServiceLink> permitted = sessions.permittedServices(counter.id(), user);
        if (permitted.isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!counter.usable()) throw refusal("counter_inactive");

        List<UUID> chosen = chosenServices(request.serviceIds(), permitted);
        if (sessions.liveSessionOfAgent(user).isPresent()) throw refusal("agent_has_open_session");
        if (sessions.liveSessionOfCounter(counter.id()).isPresent()) throw refusal("counter_occupied");

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            sessions.insertSession(id, counter.id(), user, now, chosen);
        } catch (DuplicateKeyException e) {
            // Two opens raced past the checks above; the partial unique indexes are what decided.
            throw refusal(e.getMessage() != null && e.getMessage().contains(AGENT_INDEX) ? "agent_has_open_session" : "counter_occupied");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("counter_id", counter.id().toString());
        after.put("site_id", counter.siteId().toString());
        after.put("service_ids", chosen.stream().map(UUID::toString).toList());
        audit.record(AuditEvent.of("session.opened", "counter_session", id).withAfter(after));
        return view(sessions.session(id).orElseThrow());
    }

    /** The caller's own live session with its ticket in progress, so a console can rebuild itself after a refresh (FR-AGT-004). */
    @PreAuthorize(EITHER)
    @Transactional(readOnly = true)
    public SessionResponse current() {
        UUID user = currentUser.require().userId();
        return view(sessions.liveSessionOfAgent(user).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    // ---- serving ----------------------------------------------------------------------------------------------

    /**
     * Calls the highest-scoring eligible ticket across the queues of the session's Services (FR-QUE-002, FR-QUE-030) and
     * binds it to the session (FR-QUE-031). Refused while a ticket is called or serving (FR-AGT-010).
     */
    @PreAuthorize(SERVE)
    @Transactional
    public SessionResponse callNext(UUID sessionId) {
        SessionRow session = lockOwn(sessionId);
        if (!"open".equals(session.state())) throw refusal("session_not_open");
        if (sessions.unresolved(session.id()).stream().anyMatch(t -> "called".equals(t.state()) || "serving".equals(t.state()))) {
            throw refusal("ticket_in_progress");
        }
        for (int attempt = 0; attempt < CALL_ATTEMPTS; attempt++) {
            Optional<Contender> pick = QueueEngine.pick(heads(session), queueProperties.primaryLinkToleranceMinutes());
            if (pick.isEmpty()) break;
            Contender chosen = pick.get();
            Instant now = clock.instant();
            // The head we read may have been called by another counter since: the version says so, and we pick again.
            int version = currentVersion(chosen.ticketId());
            if (sessions.call(chosen.ticketId(), version, session.id(), session.counterId(), session.agentId(), now)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("session_id", session.id().toString());
                payload.put("service_id", chosen.serviceId().toString());
                payload.put("preference_weight", chosen.preferenceWeight());
                payload.put("announce", true);
                events.append(transition(chosen.ticketId(), TicketTransition.CALL, session, payload, now));
                return view(session);
            }
        }
        throw refusal("no_ticket_waiting");
    }

    /** Starts service on the ticket this session called (FR-AGT-032). {@code ifMatch} is the version the console last saw. */
    @PreAuthorize(SERVE)
    @Transactional
    public SessionResponse startService(UUID sessionId, Integer ifMatch) {
        SessionRow session = lockOwn(sessionId);
        BoundTicket ticket = boundIn(session, TicketTransition.START_SERVICE, "no_ticket_called");
        requireVersion(ticket, ifMatch);
        Instant now = clock.instant();
        if (!sessions.startService(ticket.id(), ticket.version(), session.id(), now)) throw refusal("version_mismatch");
        events.append(transition(ticket.id(), TicketTransition.START_SERVICE, session, Map.of("session_id", session.id().toString()), now));
        return view(session);
    }

    /**
     * Completes the ticket being served with an outcome from its Service's list (FR-AGT-032) and stores its wait and
     * service time (§18.5). A session that was closing because this ticket was unresolved closes now (§19.3).
     */
    @PreAuthorize(SERVE)
    @Transactional
    public SessionResponse complete(UUID sessionId, CompleteRequest request, Integer ifMatch) {
        SessionRow session = lockOwn(sessionId);
        BoundTicket ticket = boundIn(session, TicketTransition.COMPLETE, "no_ticket_serving");
        requireVersion(ticket, ifMatch);
        String note = request == null || request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note != null && note.length() > MAX_NOTE_LENGTH) throw invalid("note", "too_long");
        OutcomeRow outcome = outcomeFor(ticket, request == null ? null : request.outcomeCodeId());

        Instant now = clock.instant();
        TicketTimings timings = TicketTimings.atClosure(ticket.queuedAt(), ticket.calledAt(), ticket.servedAt(), now);
        if (!sessions.complete(ticket.id(), ticket.version(), session.id(), now, timings.waitSeconds(), timings.serviceSeconds(), outcome == null ? null : outcome.id(), note)) {
            throw refusal("version_mismatch");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.id().toString());
        payload.put("wait_seconds", timings.waitSeconds());
        payload.put("service_seconds", timings.serviceSeconds());
        if (outcome != null) payload.put("outcome_code", outcome.code());
        events.append(transition(ticket.id(), TicketTransition.COMPLETE, session, payload, now));

        if ("closing".equals(session.state()) && sessions.unresolved(session.id()).isEmpty()) finish(session, now);
        return view(sessions.session(session.id()).orElseThrow());
    }

    // ---- closing ----------------------------------------------------------------------------------------------

    /**
     * Closes the caller's session. With a ticket still called, serving or held the session moves to {@code closing}, takes
     * no new assignments, and the request is refused until the ticket is resolved (FR-AGT-005, §19.3); completing it then
     * closes the session. The refusal keeps the move to {@code closing}, so this method does not roll back on it.
     */
    @PreAuthorize(OPEN_CLOSE)
    @Transactional(noRollbackFor = ApiException.class)
    public SessionResponse close(UUID sessionId) {
        SessionRow session = lockOwn(sessionId);
        if (!session.live()) return view(session);
        if (!sessions.unresolved(session.id()).isEmpty()) {
            if (!"closing".equals(session.state())) sessions.setState(session.id(), "closing", null);
            throw refusal("ticket_in_progress");
        }
        finish(session, clock.instant());
        return view(sessions.session(session.id()).orElseThrow());
    }

    private void finish(SessionRow session, Instant now) {
        sessions.setState(session.id(), "closed", now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("counter_id", session.counterId().toString());
        after.put("state", "closed");
        audit.record(AuditEvent.of("session.closed", "counter_session", session.id()).withAfter(after));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /**
     * The session, locked, if it is the caller's own (FR-CFG-105). Anyone else's session is {@code forbidden}: the agent
     * permissions in §5.2 are "own records only", and forcing another agent's session closed is a different action.
     */
    private SessionRow lockOwn(UUID sessionId) {
        SessionRow session = sessions.lock(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!session.agentId().equals(currentUser.require().userId())) throw new ApiException(ErrorCode.FORBIDDEN);
        return session;
    }

    /** The head of every queue the session serves that its counter still links, tagged with the link's weight. */
    private List<Contender> heads(SessionRow session) {
        List<Contender> heads = new ArrayList<>();
        for (ServiceLink link : sessions.links(session.counterId(), session.services())) {
            queues.callableHead(link.serviceId()).ifPresent(head -> heads.add(new Contender(link.serviceId(), link.weight(), head.ticketId(), head.queuedAt(), head.terms().score())));
        }
        return heads;
    }

    private int currentVersion(UUID ticketId) {
        Integer version = sessions.versionOf(ticketId);
        return version == null ? -1 : version;
    }

    /** The ticket this session holds in the state {@code transition} leaves, or a conflict named {@code reason}. */
    private BoundTicket boundIn(SessionRow session, TicketTransition transition, String reason) {
        if (!session.live()) throw refusal("session_not_open");
        return sessions.unresolved(session.id()).stream()
                .filter(t -> transition.apply(t.state()).isPresent())
                .findFirst()
                .orElseThrow(() -> refusal(reason));
    }

    private static void requireVersion(BoundTicket ticket, Integer ifMatch) {
        if (ifMatch != null && ifMatch != ticket.version()) throw refusal("version_mismatch");
    }

    /** The outcome named must belong to the ticket's Service and be active; it is required while the Service has any. */
    private OutcomeRow outcomeFor(BoundTicket ticket, UUID outcomeCodeId) {
        List<OutcomeRow> allowed = sessions.outcomes(ticket.serviceId());
        if (outcomeCodeId == null) {
            if (allowed.isEmpty()) return null;
            throw invalid("outcome_code_id", "required");
        }
        return allowed.stream().filter(o -> o.id().equals(outcomeCodeId)).findFirst().orElseThrow(() -> invalid("outcome_code_id", "invalid"));
    }

    private List<UUID> chosenServices(List<UUID> requested, List<ServiceLink> permitted) {
        if (requested == null) return permitted.stream().map(ServiceLink::serviceId).toList();
        List<UUID> allowedIds = permitted.stream().map(ServiceLink::serviceId).toList();
        if (requested.isEmpty() || requested.stream().anyMatch(id -> id == null || !allowedIds.contains(id))) throw invalid("service_ids", "invalid");
        // Keep the counter's own order and drop repeats.
        return allowedIds.stream().filter(requested::contains).toList();
    }

    private SessionResponse view(SessionRow session) {
        CounterRow counter = sessions.counter(session.counterId()).orElseThrow();
        List<SessionResponse.ServiceRef> services = sessions.links(session.counterId(), session.services()).stream().map(SessionViews::service).toList();
        SessionResponse.SessionTicket ticket = sessions.unresolved(session.id()).stream()
                .filter(t -> "called".equals(t.state()) || "serving".equals(t.state()))
                .findFirst()
                .map(t -> SessionViews.ticket(t, sessions.outcomes(t.serviceId())))
                .orElse(null);
        return new SessionResponse(session.id(), SessionViews.counter(counter), session.agentId(), session.state(), session.openedAt(), session.closedAt(), services, ticket);
    }

    private TicketEvents.Transition transition(UUID ticketId, TicketTransition transition, SessionRow session, Object payload, Instant now) {
        return new TicketEvents.Transition(ticketId, transition.eventType(), transition.from(), transition.to(), session.agentId(), STAFF, session.counterId(), payload, now, now);
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    private static ApiException refusal(String reason) {
        return new ApiException(ErrorCode.CONFLICT, Map.of("reason", reason));
    }
}
