package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.TicketRepository.NewTicket;
import com.qms.issuance.TicketRepository.ServiceTarget;
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
    private final TicketEvents events;
    private final TicketViews views;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Clock clock;

    IssuanceService(TicketRepository tickets, SequenceBlocks sequences, TicketEvents events, TicketViews views, AuditWriter audit, ScopeGuard scope, Clock clock) {
        this.tickets = tickets;
        this.sequences = sequences;
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

        Instant now = clock.instant();
        String resetKey = TokenNumbering.resetKey(now, ZoneId.of(target.timezone()));
        long sequence = sequences.next(target.siteId(), target.tokenPrefix(), resetKey);
        String tokenNumber = TokenNumbering.format(target.tokenPrefix(), sequence);
        String secret = newSecret();

        UUID visitId = UUID.randomUUID();
        tickets.insertVisit(visitId, target.siteId(), now);
        UUID ticketId = UUID.randomUUID();
        tickets.insertTicket(new NewTicket(
                ticketId, tokenNumber, sequence, resetKey, target, tickets.waitingZone(target.serviceId()), visitId, command.originChannel(), now, hash(secret)));
        events.append(new TicketEvents.Transition(
                ticketId,
                ISSUED,
                null,
                WAITING,
                command.actorId(),
                command.actorType().wire(),
                null,
                Map.of("origin_channel", command.originChannel(), "token_number", tokenNumber),
                command.deviceTime() == null ? now : command.deviceTime(),
                now));
        audit.record(AuditEvent.of(ISSUED, "ticket", ticketId).withAfter(snapshot(ticketId, tokenNumber, target, command.originChannel(), visitId)));

        return views.of(tickets.ticket(ticketId).orElseThrow()).withSecret(secret);
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

    private static Map<String, Object> snapshot(UUID ticketId, String tokenNumber, ServiceTarget target, String originChannel, UUID visitId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("token_number", tokenNumber);
        after.put("service_id", target.serviceId().toString());
        after.put("service_group_id", target.groupId().toString());
        after.put("site_id", target.siteId().toString());
        after.put("visit_id", visitId.toString());
        after.put("origin_channel", originChannel);
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
