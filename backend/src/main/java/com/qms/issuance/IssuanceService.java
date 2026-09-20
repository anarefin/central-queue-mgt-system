package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.TicketRepository.NewTicket;
import com.qms.issuance.TicketRepository.PriorityClassRef;
import com.qms.issuance.TicketRepository.ServiceTarget;
import com.qms.issuance.TokenNumbering.Period;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.PriorityPrecedence;
import com.qms.queue.QueueProperties;
import com.qms.queue.TicketEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues a ticket: the one path every channel reuses (SRS §8.5). It knows nothing about HTTP or about who the caller
 * is beyond the actor recorded on the event; whoever adapts a channel has already authorised the caller
 * (FR-CFG-103). A staff actor is further limited to the sites in their token (FR-CFG-106).
 *
 * <p>The Token number follows the numbering rule of the Service, else of its group, else the built-in default
 * (FR-CFG-018). A rule applies to tickets issued after it changes; issued tickets are never renumbered (FR-CFG-041).
 *
 * <p>One transaction covers everything (FR-ISS-001): the sequence number, the visit, the ticket row (which is its place
 * in the queue), the ticket event and the audit entry. If any step fails none of them remain, and the number is not
 * consumed.
 */
@Service
@Profile(Profiles.SERVING)
public class IssuanceService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    static final String ISSUED = "ticket.issued";
    static final String WAITING = "waiting";
    static final String REMOTE = "remote";

    private final TicketRepository tickets;
    private final SequenceBlocks sequences;
    private final NumberingRepository numbering;
    private final NumberingResets resets;
    private final TicketEvents events;
    private final TicketViews views;
    private final IssuanceGate gate;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final QueueProperties queueProperties;
    private final Clock clock;

    IssuanceService(
            TicketRepository tickets,
            SequenceBlocks sequences,
            NumberingRepository numbering,
            NumberingResets resets,
            TicketEvents events,
            TicketViews views,
            IssuanceGate gate,
            AuditWriter audit,
            ScopeGuard scope,
            QueueProperties queueProperties,
            Clock clock) {
        this.tickets = tickets;
        this.sequences = sequences;
        this.numbering = numbering;
        this.resets = resets;
        this.events = events;
        this.views = views;
        this.gate = gate;
        this.audit = audit;
        this.scope = scope;
        this.queueProperties = queueProperties;
        this.clock = clock;
    }

    @Transactional
    public TicketResponse issue(IssueCommand command) {
        ServiceTarget target = tickets.serviceTarget(command.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (command.actorType() == ActorType.STAFF) scope.requireSite(target.siteId());
        Instant now = clock.instant();
        UUID visitId = UUID.randomUUID();
        tickets.insertVisit(visitId, target.siteId(), now);
        return issue(target, command, visitId, now, WAITING, null, null);
    }

    /**
     * A visitor's remote join (ticket 42, SRS §13.2, FR-MOB-010..012): the same channel-agnostic pipeline every other
     * issuance goes through — numbering, priority, the Visit and the event log — except the ticket starts {@code remote}
     * instead of {@code waiting} (queued and accruing wait identically, but never callable until it is checked in, ticket
     * 43) and the gate additionally requires the Service's virtual-queue flag, its distance and remote-share caps and its
     * join window in place of the usual "must already be open" ({@link IssuanceGate#forRemoteJoin}). {@code latitude} and
     * {@code longitude} are the visitor's own device position, required only when the Service's policy sets a distance cap.
     */
    @Transactional
    public TicketResponse issueRemote(IssueCommand command, Double latitude, Double longitude) {
        ServiceTarget target = tickets.serviceTarget(command.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant now = clock.instant();
        UUID visitId = UUID.randomUUID();
        tickets.insertVisit(visitId, target.siteId(), now);
        return issue(target, command, visitId, now, REMOTE, latitude, longitude);
    }

    /**
     * Issues a ticket for {@code command} into a Visit that already exists, instead of creating one (ticket 31,
     * FR-ISS-022, ADR-0007): every check {@link #issue(IssueCommand)} makes still runs, only the Visit is not created
     * here. This is how a multi-stop Journey's stops share one Visit: {@code JourneyService} creates the Visit once and
     * calls this for each stop it issues, at issuance and again as an ordered Journey completes each stop in turn
     * (FR-QUE-061, FR-QUE-062).
     */
    @Transactional
    TicketResponse issueIntoVisit(IssueCommand command, UUID visitId) {
        ServiceTarget target = tickets.serviceTarget(command.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (command.actorType() == ActorType.STAFF) scope.requireSite(target.siteId());
        return issue(target, command, visitId, clock.instant(), WAITING, null, null);
    }

    /**
     * A ticket converted from a checked-in appointment (SRS §8.4, §9.4; FR-APT-030..032, FR-QUE-011, FR-QUE-020).
     * {@code appointmentPriorityClassId} is the class chosen at booking, the "appointment" source of FR-QUE-011's
     * precedence (ahead of the visitor's category, which does not exist yet, and every default); {@code queuedAt} is
     * the later of the slot time and this check-in, already worked out by the caller, so effective wait is measured
     * from there and not from {@code checkinAt} (FR-QUE-020). The caller has already checked the appointment is
     * within its check-in window and moved it {@code booked -> checked_in} (§19.2); this only ever creates the ticket
     * that moves it on to {@code converted}.
     */
    public record AppointmentCheckinCommand(
            UUID serviceId, UUID visitorId, UUID appointmentPriorityClassId, Instant queuedAt, Instant checkinAt, UUID actorId, ActorType actorType) {}

    @Transactional
    public TicketResponse issueForAppointmentCheckin(AppointmentCheckinCommand command) {
        ServiceTarget target = tickets.serviceTarget(command.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!target.active()) throw IssuanceGate.conflict("service_inactive", Map.of());
        Instant now = command.checkinAt();
        UUID visitId = UUID.randomUUID();
        tickets.insertVisit(visitId, target.siteId(), now);

        PriorityPrecedence.Choice choice = PriorityPrecedence.choose(
                null,
                command.appointmentPriorityClassId(),
                null,
                tickets.channelDefaultClass(Channels.APPOINTMENT_CHECKIN).orElse(null),
                tickets.serviceDefaultClass(target.serviceId()).orElse(null));
        UUID priorityClassId = choice.classId();
        PriorityClassRef priority = priorityClassId == null ? null : tickets.priorityClass(priorityClassId).orElseThrow();

        NumberingSpec rule = numbering.effective(target.serviceId(), target.groupId());
        String prefix = rule.prefix(target.tokenPrefix(), target.groupPrefix(), priority == null ? null : priority.prefixOverride());
        Period period = TokenNumbering.period(now, ZoneId.of(target.timezone()), rule.boundary(), rule.resetTime());
        resets.open(target.siteId(), prefix, period, rule, NumberingResets.ISSUANCE, now);
        String resetKey = period.key();
        long sequence = sequences.next(target.siteId(), prefix, resetKey, rule.start());
        String tokenNumber = rule.format(prefix, sequence);
        String secret = newSecret();

        UUID ticketId = UUID.randomUUID();
        tickets.insertTicket(new NewTicket(
                ticketId, tokenNumber, sequence, resetKey, target, tickets.waitingZone(target.serviceId()), visitId, Channels.APPOINTMENT_CHECKIN, now, command.queuedAt(),
                hash(secret), priorityClassId, command.visitorId(), null, null, null, queueProperties.appointmentBonusMinutes(), WAITING));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("origin_channel", Channels.APPOINTMENT_CHECKIN);
        payload.put("token_number", tokenNumber);
        if (choice.classId() != null) {
            payload.put("priority_class_id", choice.classId().toString());
            payload.put("priority_source", choice.source().wire());
        }
        events.append(new TicketEvents.Transition(
                ticketId, ISSUED, null, WAITING, command.actorId(), command.actorType().wire(), null, payload, now, now));
        audit.record(AuditEvent.of(ISSUED, "ticket", ticketId).withAfter(snapshot(ticketId, tokenNumber, target, Channels.APPOINTMENT_CHECKIN, visitId, priorityClassId)));

        return views.of(tickets.ticket(ticketId).orElseThrow()).withSecret(secret);
    }

    private TicketResponse issue(ServiceTarget target, IssueCommand command, UUID visitId, Instant now, String initialState, Double latitude, Double longitude) {
        gate.beforeService(command, now);
        requireIssuable(target, command.originChannel());
        if (REMOTE.equals(initialState)) {
            gate.forRemoteJoin(target, command, now, latitude, longitude);
        } else {
            gate.forService(target, command, now);
        }
        requireVisitorIdentifier(target, command.visitorId());
        requireValidCustomLevel(target.groupId(), command.customLevelId());
        requireOnDutyAgent(target.groupId(), command.targetAgentId());
        if (command.priorityClassId() != null) requireIssuableClass(command.priorityClassId());
        // The class is decided once, here, and stored on the ticket, so a change to a default later cannot move it (FR-CFG-041).
        // A ticket issued this way is never an appointment's (that path is issueForAppointmentCheckin); visitor
        // categories do not exist yet, so both sources are passed as none until one applies.
        PriorityPrecedence.Choice choice = PriorityPrecedence.choose(
                command.priorityClassId(),
                null,
                null,
                tickets.channelDefaultClass(command.originChannel()).orElse(null),
                tickets.serviceDefaultClass(target.serviceId()).orElse(null));
        UUID priorityClassId = choice.classId();
        PriorityClassRef priority = priorityClassId == null ? null : tickets.priorityClass(priorityClassId).orElseThrow();

        NumberingSpec rule = numbering.effective(target.serviceId(), target.groupId());
        String prefix = rule.prefix(target.tokenPrefix(), target.groupPrefix(), priority == null ? null : priority.prefixOverride());
        Period period = TokenNumbering.period(now, ZoneId.of(target.timezone()), rule.boundary(), rule.resetTime());
        resets.open(target.siteId(), prefix, period, rule, NumberingResets.ISSUANCE, now);
        String resetKey = period.key();
        long sequence = sequences.next(target.siteId(), prefix, resetKey, rule.start());
        String tokenNumber = rule.format(prefix, sequence);
        String secret = newSecret();

        UUID ticketId = UUID.randomUUID();
        tickets.insertTicket(new NewTicket(
                ticketId, tokenNumber, sequence, resetKey, target, tickets.waitingZone(target.serviceId()), visitId, command.originChannel(), now, now, hash(secret), priorityClassId,
                command.visitorId(), blank(command.purposeNote()), command.targetAgentId(), command.customLevelId(), 0, initialState));
        events.append(new TicketEvents.Transition(
                ticketId,
                ISSUED,
                null,
                initialState,
                command.actorId(),
                command.actorType().wire(),
                null,
                eventPayload(command, tokenNumber, choice),
                command.deviceTime() == null ? now : command.deviceTime(),
                now));
        audit.record(AuditEvent.of(ISSUED, "ticket", ticketId).withAfter(snapshot(ticketId, tokenNumber, target, command.originChannel(), visitId, priorityClassId)));

        return views.of(tickets.ticket(ticketId).orElseThrow()).withSecret(secret);
    }

    /** A class staff choose must exist and be active; the default class is what no choice means, so it needs no id. */
    private PriorityClassRef requireIssuableClass(UUID id) {
        PriorityClassRef priority = tickets.priorityClass(id).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "priority_class_id", "code", "not_found")))));
        if (!priority.active()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "priority_class_id", "code", "inactive"))));
        }
        return priority;
    }

    /** FR-CFG-013: a Service that requires a visitor identifier refuses issuance until one has resolved to a visitor. */
    private static void requireVisitorIdentifier(ServiceTarget target, UUID visitorId) {
        if ("mandatory".equals(target.visitorIdentifier()) && visitorId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "visitor_id", "code", "required"))));
        }
    }

    /** FR-ISS-010: a custom-level pick must be one of its group's currently configured options. */
    private void requireValidCustomLevel(UUID groupId, String customLevelId) {
        if (customLevelId == null) return;
        List<String> allowed = tickets.groupSelection(groupId).map(TicketRepository.GroupSelection::customLevelOptionIds).orElse(List.of());
        if (!allowed.contains(customLevelId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "custom_level_id", "code", "not_found"))));
        }
    }

    /**
     * FR-ISS-012: an individual Agent may be picked only when the group's tree offers that level (both the team and
     * individual levels enabled) and the Agent is, right now, an active team member on duty. Checked again here, at
     * write time, and not only by what {@code GET /kiosk/groups/{id}/agents} showed the kiosk a moment earlier,
     * because the Agent could have gone off duty in between.
     */
    private void requireOnDutyAgent(UUID groupId, UUID targetAgentId) {
        if (targetAgentId == null) return;
        TicketRepository.GroupSelection selection = tickets.groupSelection(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!selection.teamSelectable() || !selection.individualSelectable()) {
            throw IssuanceGate.conflict("individual_not_selectable", Map.of());
        }
        if (!tickets.agentOnDutyInGroup(groupId, targetAgentId)) {
            throw IssuanceGate.conflict("agent_not_on_duty", Map.of());
        }
    }

    private static Map<String, Object> eventPayload(IssueCommand command, String tokenNumber, PriorityPrecedence.Choice choice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("origin_channel", command.originChannel());
        payload.put("token_number", tokenNumber);
        if (choice.classId() != null) {
            payload.put("priority_class_id", choice.classId().toString());
            payload.put("priority_source", choice.source().wire());
        }
        if (command.targetAgentId() != null) payload.put("target_agent_id", command.targetAgentId().toString());
        if (command.customLevelId() != null) payload.put("custom_level_id", command.customLevelId());
        return payload;
    }

    private static void requireIssuable(ServiceTarget target, String originChannel) {
        if (!target.active()) throw IssuanceGate.conflict("service_inactive", Map.of());
        if (!target.channels().contains(originChannel)) throw IssuanceGate.conflict("channel_not_allowed", Map.of());
        boolean walkIn = !Channels.APPOINTMENT_CHECKIN.equals(originChannel);
        if (walkIn && "appointment_only".equals(target.bookingMode())) throw IssuanceGate.conflict("appointment_only", Map.of());
    }

    private static Map<String, Object> snapshot(UUID ticketId, String tokenNumber, ServiceTarget target, String originChannel, UUID visitId, UUID priorityClassId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("token_number", tokenNumber);
        after.put("service_id", target.serviceId().toString());
        after.put("service_group_id", target.groupId().toString());
        after.put("site_id", target.siteId().toString());
        after.put("visit_id", visitId.toString());
        after.put("origin_channel", originChannel);
        if (priorityClassId != null) after.put("priority_class_id", priorityClassId.toString());
        return after;
    }

    private static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blank(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** The secret has 256 bits of entropy, so a plain SHA-256 is enough to make the stored value useless on its own. */
    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
