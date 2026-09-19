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

    private final TicketRepository tickets;
    private final SequenceBlocks sequences;
    private final NumberingRepository numbering;
    private final NumberingResets resets;
    private final TicketEvents events;
    private final TicketViews views;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Clock clock;

    IssuanceService(
            TicketRepository tickets,
            SequenceBlocks sequences,
            NumberingRepository numbering,
            NumberingResets resets,
            TicketEvents events,
            TicketViews views,
            AuditWriter audit,
            ScopeGuard scope,
            Clock clock) {
        this.tickets = tickets;
        this.sequences = sequences;
        this.numbering = numbering;
        this.resets = resets;
        this.events = events;
        this.views = views;
        this.audit = audit;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional
    public TicketResponse issue(IssueCommand command) {
        ServiceTarget target = tickets.serviceTarget(command.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (command.actorType() == ActorType.STAFF) scope.requireSite(target.siteId());
        requireIssuable(target, command.originChannel());
        PriorityClassRef priority = command.priorityClassId() == null ? null : requireIssuableClass(command.priorityClassId());

        Instant now = clock.instant();
        NumberingSpec rule = numbering.effective(target.serviceId(), target.groupId());
        String prefix = rule.prefix(target.tokenPrefix(), target.groupPrefix(), priority == null ? null : priority.prefixOverride());
        Period period = TokenNumbering.period(now, ZoneId.of(target.timezone()), rule.boundary(), rule.resetTime());
        resets.open(target.siteId(), prefix, period, rule, NumberingResets.ISSUANCE, now);
        String resetKey = period.key();
        long sequence = sequences.next(target.siteId(), prefix, resetKey, rule.start());
        String tokenNumber = rule.format(prefix, sequence);
        String secret = newSecret();

        UUID visitId = UUID.randomUUID();
        tickets.insertVisit(visitId, target.siteId(), now);
        UUID ticketId = UUID.randomUUID();
        tickets.insertTicket(new NewTicket(
                ticketId, tokenNumber, sequence, resetKey, target, tickets.waitingZone(target.serviceId()), visitId, command.originChannel(), now, hash(secret), command.priorityClassId()));
        events.append(new TicketEvents.Transition(
                ticketId,
                ISSUED,
                null,
                WAITING,
                command.actorId(),
                command.actorType().wire(),
                null,
                eventPayload(command, tokenNumber),
                command.deviceTime() == null ? now : command.deviceTime(),
                now));
        audit.record(AuditEvent.of(ISSUED, "ticket", ticketId).withAfter(snapshot(ticketId, tokenNumber, target, command.originChannel(), visitId, command.priorityClassId())));

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

    private static Map<String, Object> eventPayload(IssueCommand command, String tokenNumber) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("origin_channel", command.originChannel());
        payload.put("token_number", tokenNumber);
        if (command.priorityClassId() != null) payload.put("priority_class_id", command.priorityClassId().toString());
        return payload;
    }

    private static void requireIssuable(ServiceTarget target, String originChannel) {
        if (!target.active()) throw refusal("service_inactive");
        if (!target.channels().contains(originChannel)) throw refusal("channel_not_allowed");
        boolean walkIn = !Channels.APPOINTMENT_CHECKIN.equals(originChannel);
        if (walkIn && "appointment_only".equals(target.bookingMode())) throw refusal("appointment_only");
    }

    /** The specific reasons issuance can be refused for arrive with the issuance-rules ticket; until then {@code conflict} names one. */
    private static ApiException refusal(String reason) {
        return new ApiException(ErrorCode.CONFLICT, Map.of("reason", reason));
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

    /** The secret has 256 bits of entropy, so a plain SHA-256 is enough to make the stored value useless on its own. */
    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
