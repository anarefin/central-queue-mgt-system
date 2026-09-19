package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.idempotency.IdempotencyService;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
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
 * The kiosk's own adapter of the channel-agnostic {@link IssuanceService} (ticket 25, SRS §8.2): a paired kiosk
 * device, not a staff member, is the caller, and the channel is always {@link Channels#KIOSK}. Like the reception
 * adapter ({@link TicketController}) it insists on an {@code Idempotency-Key} so a retry after a lost response (a
 * printer jam, a reload after power loss, NFR-AVL-006) never issues a second ticket. A kiosk's access token carries
 * only its own site in its {@code sites} claim (ticket 24); this controller enforces that scope itself, the way
 * {@link IssuanceService#issue} already does for a staff actor, since a device actor is deliberately left
 * unscoped there so every channel adapter can decide the shape of its own authorisation (§8.5).
 */
@RestController
@Profile(Profiles.SERVING)
public class KioskTicketController {

    private static final String ISSUE = "hasRole('KIOSK')";

    private final IssuanceService issuance;
    private final TicketRepository tickets;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;

    KioskTicketController(IssuanceService issuance, TicketRepository tickets, IdempotencyService idempotency, CurrentUser currentUser, ScopeGuard scope) {
        this.issuance = issuance;
        this.tickets = tickets;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
        this.scope = scope;
    }

    /** Issues a walk-in ticket for the Service the visitor chose at the end of the kiosk's common path (FR-ISS-010..017). */
    @PreAuthorize(ISSUE)
    @PostMapping("/kiosk/tickets")
    public ResponseEntity<TicketResponse> issue(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey, @RequestBody KioskIssueTicketRequest request) {
        IdempotencyService.requireUsable(idempotencyKey);
        if (request == null || request.serviceId() == null) throw invalid("service_id", "required");

        TicketRepository.ServiceTarget target = tickets.serviceTarget(request.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(target.siteId());

        UUID deviceId = currentUser.require().userId();
        var command = new IssueCommand(request.serviceId(), Channels.KIOSK, deviceId, ActorType.DEVICE, null);
        var result = idempotency.execute(
                "POST /kiosk/tickets:" + deviceId, idempotencyKey, request.serviceId().toString(), TicketResponse.class, () -> issuance.issue(command));
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) response.header(TicketController.REPLAYED_HEADER, "true");
        return response.body(result.value());
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
