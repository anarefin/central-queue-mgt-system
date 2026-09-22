package com.qms.integration.serviceaccount;

import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.TicketResponse;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.idempotency.IdempotencyService;
import com.qms.platform.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A host system's own adapter of the channel-agnostic {@link IssuanceService} (ticket 58, SRS §22.4, FR-INT-030): a
 * service account, not a staff member or a device, is the caller. The channel recorded is {@link Channels#MOBILE}:
 * a host system plays exactly the role SRS §22.4 itself describes ("a bank's mobile app or a hospital's patient
 * portal"), the closed {@code ticket.origin_channel} check constraint already has no fifth value to spend on this,
 * and a service still needs {@code mobile} enabled among its channels (FR-CFG-010) before a host system may issue
 * to it — the same per-service gate every other channel already goes through, not a privileged bypass. Like the
 * reception and kiosk adapters this insists on an {@code Idempotency-Key} so a retry after a lost response never
 * issues a second ticket (§20.1). {@link IssuanceService#issue} itself checks the caller's site scope for this actor
 * type, the same way it already does for a staff actor (FR-CFG-106).
 */
@RestController
@Profile(Profiles.SERVING)
public class HostSystemTicketController {

    private static final String ISSUE = "hasRole('HOST_SYSTEM')";
    /** Same header and value {@code issuance.TicketController} replies with on a replayed key. */
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IssuanceService issuance;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;

    HostSystemTicketController(IssuanceService issuance, IdempotencyService idempotency, CurrentUser currentUser) {
        this.issuance = issuance;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
    }

    /** Issues a ticket on a host system's own visitor's behalf. The response carries the same one-time {@code
     * secret} every other channel's does (§20.5); the host system passes it back to its visitor, who may then use
     * it against the same anonymous, public {@code GET /tickets/{id}/visitor} and {@code POST
     * /tickets/{id}/visitor-cancel} endpoints a browser-based visitor already uses to query queue status and cancel
     * — no separate host-system-only query or cancel path, and so no privileged internal one either (§20). */
    @PreAuthorize(ISSUE)
    @PostMapping("/host/tickets")
    public ResponseEntity<TicketResponse> issue(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey, @RequestBody HostSystemIssueTicketRequest request) {
        IdempotencyService.requireUsable(idempotencyKey);
        if (request == null || request.serviceId() == null) throw invalid();

        UUID serviceAccountId = currentUser.require().userId();
        var command = new IssueCommand(
                request.serviceId(), Channels.MOBILE, serviceAccountId, ActorType.HOST_SYSTEM, null, null, request.visitorId(), false,
                request.purposeNote(), null, null);
        var result = idempotency.execute(
                "POST /host/tickets:" + serviceAccountId, idempotencyKey, fingerprint(request), TicketResponse.class, () -> issuance.issue(command));
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) response.header(REPLAYED_HEADER, "true");
        return response.body(result.value());
    }

    private static String fingerprint(HostSystemIssueTicketRequest request) {
        return request.serviceId() + "|" + request.visitorId() + "|" + request.purposeNote();
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "service_id", "code", "required"))));
    }
}
