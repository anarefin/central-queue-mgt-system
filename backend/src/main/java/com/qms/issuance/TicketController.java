package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.idempotency.IdempotencyService;
import com.qms.platform.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tickets and queues over HTTP (SRS §20.4). {@code POST /tickets} is the staff (reception) adapter of the
 * channel-agnostic {@link IssuanceService}: it authorises the caller, insists on an {@code Idempotency-Key} so a retry
 * after a lost response never issues a second ticket (§20.1), and fixes the channel to the caller's. Each method carries
 * its permission here, where the build-time check looks for it (FR-CFG-108).
 */
@RestController
@Profile(Profiles.SERVING)
public class TicketController {

    private static final String ISSUE = "hasAuthority(T(com.qms.platform.security.Authorities).TICKET_ISSUE)";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IssuanceService issuance;
    private final TicketQueries queries;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;

    TicketController(IssuanceService issuance, TicketQueries queries, IdempotencyService idempotency, CurrentUser currentUser) {
        this.issuance = issuance;
        this.queries = queries;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
    }

    /** Issues a ticket. Replaying the key within 24 hours returns the original ticket, secret included, and issues nothing. */
    @PreAuthorize(ISSUE)
    @PostMapping("/tickets")
    public ResponseEntity<TicketResponse> issue(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey, @RequestBody IssueTicketRequest request) {
        IdempotencyService.requireUsable(idempotencyKey);
        if (request == null || request.serviceId() == null) throw invalid("service_id", "required");
        String channel = request.originChannel() == null ? Channels.RECEPTION : request.originChannel();
        if (!Channels.ALL.contains(channel)) throw invalid("origin_channel", "invalid");
        // Only staff can call this endpoint so far, and staff issue at reception. Kiosks and the mobile app arrive with
        // their own principals and adapters.
        if (!Channels.RECEPTION.equals(channel)) throw new ApiException(ErrorCode.FORBIDDEN);

        UUID actor = currentUser.require().userId();
        var command = new IssueCommand(request.serviceId(), channel, actor, ActorType.STAFF, request.occurredAt());
        var result = idempotency.execute(
                "POST /tickets:" + actor, idempotencyKey, fingerprint(request.serviceId(), channel), TicketResponse.class, () -> issuance.issue(command));
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) response.header(REPLAYED_HEADER, "true");
        return response.body(result.value());
    }

    @PreAuthorize(TicketQueries.STAFF)
    @GetMapping("/tickets/{id}")
    public TicketResponse ticket(@PathVariable UUID id) {
        return queries.ticket(id);
    }

    @PreAuthorize(TicketQueries.STAFF)
    @GetMapping("/queues/{serviceId}")
    public QueueSnapshot queue(@PathVariable UUID serviceId, @RequestParam(required = false) Integer limit) {
        return queries.queue(serviceId, limit);
    }

    @PreAuthorize(TicketQueries.STAFF)
    @GetMapping("/sites/{siteId}/services")
    public SiteServices siteServices(@PathVariable UUID siteId, @RequestParam(required = false) String channel) {
        return queries.siteServices(siteId, channel);
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    /** What makes two requests the same one: the service and the channel. The device's clock is not part of it. */
    private static String fingerprint(UUID serviceId, String channel) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((serviceId + "|" + channel).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
