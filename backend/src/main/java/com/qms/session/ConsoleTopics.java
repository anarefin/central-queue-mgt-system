package com.qms.session;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.TopicSource;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Permission;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueReads;
import com.qms.queue.WaitEstimates;
import com.qms.session.SessionRepository.BoundTicket;
import com.qms.session.SessionRepository.CounterRow;
import com.qms.session.SessionRepository.ServiceScope;
import com.qms.session.SessionRepository.SessionRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The topics a console listens to: {@code queue:{service_id}} and {@code counter:{counter_id}} (SRS §21.2). Who may
 * subscribe is decided here, at subscribe time and on the server, from the token's permissions and scope (FR-QUE-080,
 * FR-CFG-103): a permission of the §5.2 matrix that covers watching a queue or a counter, inside the token's sites and
 * groups. A permission granted for "own records only" reaches only what the caller works on: the queues of the Service
 * groups whose team they are on, and the counters they may occupy.
 */
@Component
class ConsoleTopics implements TopicSource {

    /** Who watches a queue: a console (serve tickets) or a dashboard. */
    private static final List<Permission> QUEUE_VIEWERS = List.of(Permission.TICKET_CALL_SERVE_COMPLETE, Permission.DASHBOARD_VIEW_ALL, Permission.DASHBOARD_VIEW_OWN_GROUPS);
    /** Who watches a counter: whoever runs its session or serves from it. */
    private static final List<Permission> COUNTER_VIEWERS = List.of(Permission.COUNTER_SESSION_OPEN_CLOSE, Permission.TICKET_CALL_SERVE_COMPLETE);
    /** How many of the next tickets a queue snapshot lists. */
    private static final int NEXT = 5;

    private final SessionRepository sessions;
    private final QueueReads queues;
    private final WaitEstimates estimates;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;

    ConsoleTopics(SessionRepository sessions, QueueReads queues, WaitEstimates estimates, CurrentUser currentUser, ScopeGuard scope) {
        this.sessions = sessions;
        this.queues = queues;
        this.estimates = estimates;
        this.currentUser = currentUser;
        this.scope = scope;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.QUEUE) || topic.startsWith(Topics.COUNTER);
    }

    @Override
    public void authorize(String topic) {
        if (topic.startsWith(Topics.QUEUE)) authorizeQueue(id(topic, Topics.QUEUE));
        else authorizeCounter(id(topic, Topics.COUNTER));
    }

    @Override
    public Map<String, Object> snapshot(String topic) {
        return topic.startsWith(Topics.QUEUE) ? queueSnapshot(id(topic, Topics.QUEUE)) : counterSnapshot(id(topic, Topics.COUNTER));
    }

    // ---- queue:{service_id} -----------------------------------------------------------------------------------

    private void authorizeQueue(UUID serviceId) {
        Reach reach = reach(QUEUE_VIEWERS);
        ServiceScope service = sessions.serviceScope(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(service.siteId());
        scope.requireGroup(service.groupId());
        if (reach == Reach.OWN && !sessions.onTeamOf(service.groupId(), currentUser.require().userId())) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    /** Waiting count, the next tickets in order and the estimate a visitor joining now would be given (§21.2, FR-QUE-040). */
    private Map<String, Object> queueSnapshot(UUID serviceId) {
        List<Map<String, Object>> next = queues.next(serviceId, NEXT).stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ticket_id", entry.ticketId().toString());
                    row.put("token_number", entry.tokenNumber());
                    row.put("position", entry.position());
                    row.put("queued_at", entry.queuedAt().toString());
                    return row;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service_id", serviceId.toString());
        int waiting = queues.waitingCount(serviceId);
        data.put("waiting_count", waiting);
        data.put("next", next);
        data.put("estimated_wait_minutes", estimates.ahead(serviceId, waiting));
        return data;
    }

    // ---- counter:{counter_id} ---------------------------------------------------------------------------------

    private void authorizeCounter(UUID counterId) {
        Reach reach = reach(COUNTER_VIEWERS);
        CounterRow counter = sessions.counter(counterId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(counter.siteId());
        if (reach == Reach.OWN && sessions.permittedServices(counterId, currentUser.require().userId()).isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    /** The counter's live session and the ticket it is calling or serving (§21.2: assigned ticket, session state). */
    private Map<String, Object> counterSnapshot(UUID counterId) {
        CounterRow counter = sessions.counter(counterId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Optional<SessionRow> live = sessions.liveSessionOfCounter(counterId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("counter_id", counterId.toString());
        data.put("label", counter.label());
        data.put("session", live.map(session -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", session.id().toString());
            row.put("agent_id", session.agentId().toString());
            row.put("state", session.state());
            return row;
        }).orElse(null));
        data.put("ticket", live.flatMap(session -> sessions.unresolved(session.id()).stream()
                        .filter(t -> "called".equals(t.state()) || "serving".equals(t.state()))
                        .findFirst())
                .map(ConsoleTopics::ticket)
                .orElse(null));
        return data;
    }

    private static Map<String, Object> ticket(BoundTicket ticket) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ticket.id().toString());
        row.put("token_number", ticket.tokenNumber());
        row.put("state", ticket.state());
        row.put("version", ticket.version());
        return row;
    }

    // ---- who may look -----------------------------------------------------------------------------------------

    private enum Reach { ALL, OWN }

    /** How far the caller's permissions reach, or {@code forbidden} when none of {@code permissions} is theirs at all. */
    private static Reach reach(List<Permission> permissions) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new ApiException(ErrorCode.UNAUTHENTICATED);
        List<String> granted = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        if (permissions.stream().anyMatch(p -> granted.contains(p.authority()))) return Reach.ALL;
        if (permissions.stream().anyMatch(p -> granted.contains(p.ownAuthority()))) return Reach.OWN;
        throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private static UUID id(String topic, String prefix) {
        try {
            return UUID.fromString(topic.substring(prefix.length()));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
