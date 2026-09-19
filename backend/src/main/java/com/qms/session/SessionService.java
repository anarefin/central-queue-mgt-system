package com.qms.session;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.Authz;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Permission;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueEngine;
import com.qms.queue.QueueEngine.Contender;
import com.qms.queue.QueueProperties;
import com.qms.queue.QueueReads;
import com.qms.queue.ReentryPosition;
import com.qms.queue.TicketEvents;
import com.qms.queue.TicketTimings;
import com.qms.queue.TicketTransition;
import com.qms.queue.TransferRules;
import com.qms.session.SessionRepository.BoundTicket;
import com.qms.session.SessionRepository.CounterRow;
import com.qms.session.SessionRepository.OutcomeRow;
import com.qms.session.SessionRepository.ServiceLink;
import com.qms.session.SessionRepository.ServiceScope;
import com.qms.session.SessionRepository.SessionRow;
import com.qms.session.SessionRepository.TransferAgent;
import com.qms.session.SessionRepository.TransferService;
import com.qms.session.SessionRepository.TransferSource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /** Only the unrestricted permission, not "own records only": an agent cannot force-close a session, not even their own. */
    static final String FORCE_CLOSE = "hasAuthority(T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE)";
    static final String SERVE = "hasAnyAuthority(T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE,"
            + " T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE + ':own')";
    static final String EITHER = "hasAnyAuthority(T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE,"
            + " T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE + ':own',"
            + " T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE,"
            + " T(com.qms.platform.security.Authorities).TICKET_CALL_SERVE_COMPLETE + ':own')";

    /** Agents transfer the ticket they are serving (own records only); Org and Team Admins, within their scope, any ticket in service (§5.2). */
    static final String TRANSFER = "hasAnyAuthority(T(com.qms.platform.security.Authorities).TICKET_TRANSFER,"
            + " T(com.qms.platform.security.Authorities).TICKET_TRANSFER + ':own')";

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
    private final Authz authz;
    private final CurrentUser currentUser;
    private final QueueProperties queueProperties;
    private final RealtimePublisher realtime;
    private final Clock clock;

    SessionService(
            SessionRepository sessions,
            QueueReads queues,
            TicketEvents events,
            AuditWriter audit,
            ScopeGuard scope,
            Authz authz,
            CurrentUser currentUser,
            QueueProperties queueProperties,
            RealtimePublisher realtime,
            Clock clock) {
        this.sessions = sessions;
        this.queues = queues;
        this.events = events;
        this.audit = audit;
        this.scope = scope;
        this.authz = authz;
        this.currentUser = currentUser;
        this.queueProperties = queueProperties;
        this.realtime = realtime;
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
        announce("session.opened", id, counter.id(), user, "open", now);
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
        // A ticket that was missed and called again waited in stints; only time in waiting counts (Invariant 1).
        TicketTimings timings = new TicketTimings(TicketTimings.accruedWait(ticket.queuedAt(), events.changes(ticket.id())), TicketTimings.seconds(ticket.servedAt(), now));
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

    /**
     * Replays the call of the ticket this session called (F3, FR-DSP-028, ADR-0005). The ticket stays {@code called} with its
     * binding; it counts one more announcement, up to the repeat limit. It does not count towards the miss limit.
     */
    @PreAuthorize(SERVE)
    @Transactional
    public SessionResponse reannounce(UUID sessionId, Integer ifMatch) {
        SessionRow session = lockOwn(sessionId);
        BoundTicket ticket = boundIn(session, TicketTransition.REANNOUNCE, "no_ticket_called");
        requireVersion(ticket, ifMatch);
        if (!TicketTransition.mayReannounce(ticket.announceCount(), queueProperties.announceRepeatLimit())) throw refusal("reannounce_limit_reached");
        if (!sessions.reannounce(ticket.id(), ticket.version(), session.id())) throw refusal("version_mismatch");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.id().toString());
        payload.put("announce", true);
        payload.put("announce_count", ticket.announceCount() + 1);
        events.append(transition(ticket.id(), TicketTransition.REANNOUNCE, session, payload, clock.instant()));
        return view(sessions.session(session.id()).orElseThrow());
    }

    /**
     * The visitor is absent (F6, FR-QUE-050, ADR-0005). The ticket returns to waiting at the configured re-entry position,
     * applied as a Score adjustment with {@code queued_at} untouched (FR-QUE-051, ADR-0004), and its counter is free; when
     * this Miss would take {@code miss_count} past the limit the ticket closes as {@code no_show} instead. Either way the
     * binding is cleared (Invariant 2), and a closing session that this resolves closes now (§19.3).
     */
    @PreAuthorize(SERVE)
    @Transactional
    public SessionResponse miss(UUID sessionId, Integer ifMatch) {
        SessionRow session = lockOwn(sessionId);
        BoundTicket ticket = boundIn(session, TicketTransition.MISS, "no_ticket_called");
        requireVersion(ticket, ifMatch);
        Instant now = clock.instant();
        TicketTransition result = TicketTransition.onMiss(ticket.missCount(), queueProperties.missLimit());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.id().toString());
        payload.put("miss_count", ticket.missCount() + 1);
        if (result == TicketTransition.NO_SHOW) {
            int waitSeconds = TicketTimings.accruedWait(ticket.queuedAt(), events.changes(ticket.id()));
            if (!sessions.noShow(ticket.id(), ticket.version(), session.id(), now, waitSeconds)) throw refusal("version_mismatch");
            payload.put("wait_seconds", waitSeconds);
        } else {
            ReentryPosition position = queueProperties.missReentryPosition();
            int adjustment = queues.reentryAdjustment(ticket.id(), position, queueProperties.missReentryAfter());
            if (!sessions.miss(ticket.id(), ticket.version(), session.id(), adjustment)) throw refusal("version_mismatch");
            payload.put("reentry_position", position.name().toLowerCase(Locale.ROOT));
            if (position == ReentryPosition.AFTER_N) payload.put("reentry_after", queueProperties.missReentryAfter());
            payload.put("score_adjustment_minutes", adjustment);
        }
        events.append(transition(ticket.id(), result, session, payload, now));

        if ("closing".equals(session.state()) && sessions.unresolved(session.id()).isEmpty()) finish(session, now);
        return view(sessions.session(session.id()).orElseThrow());
    }

    /**
     * F8. Without a {@code ticket_id} it parks the ticket being served (FR-AGT-013): it leaves the general queue's reach but
     * stays bound to this session (ADR-0008), the counter is free to call next, and the session may hold at most the hold
     * limit. With a {@code ticket_id} it resumes that held ticket, which only the session that holds it can do.
     */
    @PreAuthorize(SERVE)
    @Transactional
    public SessionResponse hold(UUID sessionId, HoldRequest request, Integer ifMatch) {
        SessionRow session = lockOwn(sessionId);
        return request != null && request.ticketId() != null ? resume(session, request.ticketId(), ifMatch) : hold(session, ifMatch);
    }

    private SessionResponse hold(SessionRow session, Integer ifMatch) {
        // A closing session is trying to empty itself: parking a ticket there would only add to what must be cleared.
        if (!"open".equals(session.state())) throw refusal("session_not_open");
        BoundTicket ticket = boundIn(session, TicketTransition.HOLD, "no_ticket_serving");
        requireVersion(ticket, ifMatch);
        int held = (int) sessions.unresolved(session.id()).stream().filter(t -> "held".equals(t.state())).count();
        if (!TicketTransition.mayHold(held, queueProperties.holdLimit())) throw refusal("hold_limit_reached");
        if (!sessions.hold(ticket.id(), ticket.version(), session.id())) throw refusal("version_mismatch");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.id().toString());
        payload.put("held_count", held + 1);
        events.append(transition(ticket.id(), TicketTransition.HOLD, session, payload, clock.instant()));
        return view(sessions.session(session.id()).orElseThrow());
    }

    private SessionResponse resume(SessionRow session, UUID ticketId, Integer ifMatch) {
        if (!session.live()) throw refusal("session_not_open");
        List<BoundTicket> unresolved = sessions.unresolved(session.id());
        // The counter serves one ticket at a time (FR-AGT-010): the one in progress is finished, or held, first.
        if (unresolved.stream().anyMatch(t -> "called".equals(t.state()) || "serving".equals(t.state()))) throw refusal("ticket_in_progress");
        BoundTicket ticket = unresolved.stream()
                .filter(t -> t.id().equals(ticketId) && TicketTransition.RESUME.apply(t.state()).isPresent())
                .findFirst()
                .orElseThrow(() -> refusal("no_ticket_held"));
        requireVersion(ticket, ifMatch);
        if (!sessions.resume(ticket.id(), ticket.version(), session.id())) throw refusal("version_mismatch");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.id().toString());
        payload.put("resumed", true);
        events.append(transition(ticket.id(), TicketTransition.RESUME, session, payload, clock.instant()));
        return view(sessions.session(session.id()).orElseThrow());
    }

    // ---- transfer ---------------------------------------------------------------------------------------------

    /**
     * F7 (FR-QUE-052, ADR-0006). The ticket in service closes as {@code transferred}, and in the same transaction a successor
     * ticket with the same Visit, token number and Priority class is created in the target queue: a Service, or one of its Counters
     * or Agents. The predecessor's wait stops here and the successor's starts here, with the transfer Head start added so the
     * visitor is not sent to the back (FR-QUE-053). A successor targeted at an Agent waits in that Agent's personal queue (FR-QUE-003).
     * The note is mandatory. The target must be active and in the ticket's own site (§19.1, ADR-0002). An agent transfers only the
     * ticket their own session is serving; an admin, any ticket in service within their scope. A closing session that this
     * resolves closes now (§19.3).
     */
    @PreAuthorize(TRANSFER)
    @Transactional
    public TransferResponse transfer(UUID ticketId, TransferRequest request, Integer ifMatch) {
        UUID user = currentUser.require().userId();
        TransferSource source = sessions.transferSource(ticketId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(source.siteId());
        scope.requireGroup(source.groupId());
        // The session is locked first, like every other action on it, then the ticket is read again under the lock.
        SessionRow session = source.sessionId() == null ? null : sessions.lock(source.sessionId()).orElse(null);
        if (!authz.has(Permission.TICKET_TRANSFER) && (session == null || !session.agentId().equals(user))) throw new ApiException(ErrorCode.FORBIDDEN);
        source = sessions.transferSource(ticketId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        if (request == null) throw invalid("note", "required");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note == null) throw invalid("note", "required");
        if (note.length() > MAX_NOTE_LENGTH) throw invalid("note", "too_long");
        if (request.counterId() != null && request.agentId() != null) throw invalid("agent_id", "exclusive");
        boolean narrowed = request.counterId() != null || request.agentId() != null;
        if (request.serviceId() == null && !narrowed) throw invalid("service_id", "required");
        UUID serviceId = request.serviceId() != null ? request.serviceId() : source.serviceId();
        if (serviceId.equals(source.serviceId()) && !narrowed) throw invalid("service_id", "same_service");

        if (TicketTransition.TRANSFER.apply(source.state()).isEmpty() || session == null) throw refusal("no_ticket_serving");
        if (!session.live()) throw refusal("session_not_open");
        if (ifMatch != null && ifMatch != source.version()) throw refusal("version_mismatch");

        TransferService target = sessions.transferService(serviceId).orElseThrow(() -> invalid("service_id", "invalid"));
        if (!target.siteId().equals(source.siteId())) throw refusal("transfer_cross_site");
        if (!target.active()) throw refusal("transfer_target_inactive");
        UUID zoneId = null;
        if (request.counterId() != null) {
            CounterRow counter = sessions.counter(request.counterId()).orElseThrow(() -> invalid("counter_id", "invalid"));
            if (!counter.siteId().equals(source.siteId())) throw refusal("transfer_cross_site");
            if (!counter.usable()) throw refusal("transfer_target_inactive");
            if (!sessions.counterServes(counter.id(), serviceId)) throw refusal("transfer_target_mismatch");
            zoneId = counter.zoneId();
        }
        if (request.agentId() != null) {
            TransferAgent agent = sessions.transferAgent(request.agentId(), source.siteId()).orElseThrow(() -> invalid("agent_id", "invalid"));
            if (!agent.active()) throw refusal("transfer_target_inactive");
            // Someone who is not on the team of the Service's group, or who does not work at this site, cannot serve it.
            if (!agent.atSite() || !sessions.onTeamOf(target.groupId(), agent.id())) throw refusal("transfer_target_mismatch");
        }
        if (zoneId == null) zoneId = sessions.waitingZone(serviceId);

        Instant now = clock.instant();
        // The predecessor's wait stops here and its service time is what it took to serve (Invariant 1, §18.5).
        int waitSeconds = TicketTimings.accruedWait(source.queuedAt(), events.changes(source.id()));
        int serviceSeconds = TicketTimings.seconds(source.servedAt(), now);
        int headStart = TransferRules.headStartMinutes(queueProperties.transferHeadstartMinutes(), waitSeconds);
        UUID successorId = UUID.randomUUID();
        if (!sessions.transfer(source.id(), source.version(), session.id(), now, waitSeconds, serviceSeconds, note)) throw refusal("version_mismatch");
        sessions.insertSuccessor(successorId, source.id(), serviceId, target.groupId(), zoneId, now, headStart, request.counterId(), request.agentId());

        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("session_id", session.id().toString());
        closed.put("note", note);
        closed.put("successor_ticket_id", successorId.toString());
        closed.put("service_id", serviceId.toString());
        if (request.counterId() != null) closed.put("target_counter_id", request.counterId().toString());
        if (request.agentId() != null) closed.put("target_agent_id", request.agentId().toString());
        closed.put("wait_seconds", waitSeconds);
        closed.put("service_seconds", serviceSeconds);
        closed.put("head_start_minutes", headStart);
        events.append(new TicketEvents.Transition(source.id(), TicketTransition.TRANSFER.eventType(), TicketTransition.TRANSFER.from(), TicketTransition.TRANSFER.to(), user, STAFF, session.counterId(), closed, now, now));

        // The successor's first event: it joins the target queue, so that queue's consoles and displays hear of it (Invariant 3).
        Map<String, Object> joined = new LinkedHashMap<>();
        joined.put("origin", "transfer");
        joined.put("predecessor_ticket_id", source.id().toString());
        joined.put("note", note);
        joined.put("head_start_minutes", headStart);
        if (request.counterId() != null) joined.put("target_counter_id", request.counterId().toString());
        if (request.agentId() != null) joined.put("target_agent_id", request.agentId().toString());
        events.append(new TicketEvents.Transition(successorId, "ticket.issued", null, "waiting", user, STAFF, null, joined, now, now));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", source.state());
        before.put("service_id", source.serviceId().toString());
        before.put("counter_id", session.counterId().toString());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", TicketTransition.TRANSFER.to());
        after.put("successor_ticket_id", successorId.toString());
        after.put("service_id", serviceId.toString());
        if (request.counterId() != null) after.put("target_counter_id", request.counterId().toString());
        if (request.agentId() != null) after.put("target_agent_id", request.agentId().toString());
        audit.record(AuditEvent.of("ticket.transferred", "ticket", source.id()).withBefore(before).withAfter(after).withReason(note));

        if ("closing".equals(session.state()) && sessions.unresolved(session.id()).isEmpty()) finish(session, now);
        return new TransferResponse(
                new TransferResponse.Predecessor(source.id(), source.tokenNumber(), TicketTransition.TRANSFER.to()),
                new TransferResponse.Successor(
                        successorId,
                        source.tokenNumber(),
                        "waiting",
                        new SessionResponse.Named(serviceId, target.names()),
                        sessions.visitOf(successorId),
                        source.id(),
                        request.counterId(),
                        request.agentId(),
                        headStart,
                        queues.positionOf(successorId)),
                view(sessions.session(session.id()).orElseThrow()));
    }

    /**
     * The places the ticket in service may go (F7): the active Services of the session's site and the counters and agents that
     * can take each, so the console can offer them. The session's own counter and agent are left out. Transfers are intra-site
     * (ADR-0002), so nothing of another site appears.
     */
    @PreAuthorize(TRANSFER)
    @Transactional(readOnly = true)
    public TransferTargets transferTargets(UUID sessionId) {
        SessionRow session = sessions.session(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        CounterRow own = sessions.counter(session.counterId()).orElseThrow();
        scope.requireSite(own.siteId());
        if (!authz.has(Permission.TICKET_TRANSFER) && !session.agentId().equals(currentUser.require().userId())) throw new ApiException(ErrorCode.FORBIDDEN);
        return new TransferTargets(
                sessions.transferServices(own.siteId()).stream().map(s -> new TransferTargets.Service(s.id(), s.names())).toList(),
                sessions.targetCounters(own.siteId()).stream()
                        .filter(c -> !c.id().equals(own.id()))
                        .map(c -> new TransferTargets.CounterTarget(c.id(), c.label(), c.zoneName(), c.serviceIds()))
                        .toList(),
                sessions.targetAgents(own.siteId()).stream()
                        .filter(a -> !a.id().equals(session.agentId()))
                        .map(a -> new TransferTargets.AgentTarget(a.id(), a.name(), a.serviceIds()))
                        .toList());
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
        List<BoundTicket> unresolved = sessions.unresolved(session.id());
        if (!unresolved.isEmpty()) {
            if (!"closing".equals(session.state())) sessions.setState(session.id(), "closing", null);
            // With only held tickets left the agent has nothing in progress, only a list to clear (FR-AGT-013).
            boolean inProgress = unresolved.stream().anyMatch(t -> "called".equals(t.state()) || "serving".equals(t.state()));
            throw refusal(inProgress ? "ticket_in_progress" : "held_tickets_remaining");
        }
        finish(session, clock.instant());
        return view(sessions.session(session.id()).orElseThrow());
    }

    /**
     * An Org or Team Admin closes a session that is stale (FR-AGT-002): its called, serving and held tickets return to
     * {@code waiting} at the front of their queues through a Score adjustment, their bindings are cleared (ADR-0008,
     * Invariant 2) and an audit entry is written. The agent's own console learns of it through {@code session.closed}.
     * The admin's site and Service group scope decide which sessions they may reach (FR-CFG-106).
     */
    @PreAuthorize(FORCE_CLOSE)
    @Transactional
    public SessionResponse forceClose(UUID sessionId, ForceCloseRequest request) {
        SessionRow session = sessions.lock(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        CounterRow counter = sessions.counter(session.counterId()).orElseThrow();
        scope.requireSite(counter.siteId());
        List<UUID> groups = session.services().stream().map(sessions::serviceScope).flatMap(Optional::stream).map(ServiceScope::groupId).distinct().toList();
        if (!groups.isEmpty() && scope.groups(groups).isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!session.live()) throw refusal("session_not_open");
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        if (reason != null && reason.length() > MAX_NOTE_LENGTH) throw invalid("reason", "too_long");

        returnTicketsAndClose(session, "session_force_closed", reason);
        return view(sessions.session(session.id()).orElseThrow());
    }

    /**
     * Ends the live session of an Agent whose account was just disabled (FR-CFG-104): the same return of called, serving and
     * held tickets to the front of their queues as a force-close, audited as such with the cause {@code user_disabled}. It runs
     * inside the disabling transaction, on behalf of an administrator who is not necessarily allowed to force-close (the
     * disable itself is what was authorised), so it takes no scope check. Does nothing if the user has no live session.
     */
    @Transactional
    public void closeForDisabledUser(UUID userId) {
        sessions.liveSessionOfAgent(userId).flatMap(live -> sessions.lock(live.id())).filter(SessionRow::live).ifPresent(session -> returnTicketsAndClose(session, "user_disabled", "user_disabled"));
    }

    private void returnTicketsAndClose(SessionRow session, String cause, String reason) {
        Instant now = clock.instant();
        // Each ticket goes to the front in turn, so the one that joined the queue first is returned last and ends up first.
        List<BoundTicket> returning = new ArrayList<>(sessions.unresolved(session.id()));
        returning.sort(Comparator.comparing(BoundTicket::queuedAt).reversed().thenComparing(BoundTicket::id));
        List<Map<String, Object>> returned = new ArrayList<>();
        for (BoundTicket ticket : returning) {
            TicketTransition transition = TicketTransition.returnFrom(ticket.state()).orElseThrow();
            int adjustment = queues.reentryAdjustment(ticket.id(), ReentryPosition.FRONT, 1);
            if (!sessions.returnToQueue(ticket.id(), ticket.version(), session.id(), transition, adjustment)) throw refusal("version_mismatch");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session_id", session.id().toString());
            payload.put("reason", cause);
            payload.put("reentry_position", ReentryPosition.FRONT.name().toLowerCase(Locale.ROOT));
            payload.put("score_adjustment_minutes", adjustment);
            events.append(transition(ticket.id(), transition, session, payload, now));
            returned.add(Map.of("ticket_id", ticket.id().toString(), "token_number", ticket.tokenNumber(), "from_state", ticket.state()));
        }

        sessions.setState(session.id(), "force_closed", now);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("counter_id", session.counterId().toString());
        before.put("agent_id", session.agentId().toString());
        before.put("state", session.state());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", "force_closed");
        after.put("returned_tickets", returned);
        AuditEvent event = AuditEvent.of("session.force_closed", "counter_session", session.id()).withBefore(before).withAfter(after);
        audit.record(reason == null ? event : event.withReason(reason));
        announce("session.closed", session.id(), session.counterId(), session.agentId(), "force_closed", now);
    }

    private void finish(SessionRow session, Instant now) {
        sessions.setState(session.id(), "closed", now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("counter_id", session.counterId().toString());
        after.put("state", "closed");
        audit.record(AuditEvent.of("session.closed", "counter_session", session.id()).withAfter(after));
        announce("session.closed", session.id(), session.counterId(), session.agentId(), "closed", now);
    }

    /** Tells the counter's console its session changed (SRS §21.4); the hub delivers it once this transaction commits. */
    private void announce(String type, UUID sessionId, UUID counterId, UUID agentId, String state, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", sessionId.toString());
        data.put("counter_id", counterId.toString());
        data.put("agent_id", agentId.toString());
        data.put("state", state);
        realtime.publish(Topics.counter(counterId), type, now, data);
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
            queues.callableHead(link.serviceId(), session.counterId(), session.agentId()).ifPresent(head -> heads.add(new Contender(link.serviceId(), link.weight(), head.ticketId(), head.queuedAt(), head.terms().score())));
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
        List<BoundTicket> unresolved = sessions.unresolved(session.id());
        SessionResponse.SessionTicket ticket = unresolved.stream()
                .filter(t -> "called".equals(t.state()) || "serving".equals(t.state()))
                .findFirst()
                .map(this::ticketView)
                .orElse(null);
        List<SessionResponse.SessionTicket> held = unresolved.stream().filter(t -> "held".equals(t.state())).map(this::ticketView).toList();
        return new SessionResponse(
                session.id(), SessionViews.counter(counter), session.agentId(), session.state(), session.openedAt(), session.closedAt(), services, ticket, held,
                queueProperties.holdLimit());
    }

    private SessionResponse.SessionTicket ticketView(BoundTicket t) {
        return SessionViews.ticket(t, sessions.outcomes(t.serviceId()), queueProperties.announceRepeatLimit(), queueProperties.missLimit());
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
