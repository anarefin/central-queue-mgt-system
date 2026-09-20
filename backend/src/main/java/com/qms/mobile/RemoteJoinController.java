package com.qms.mobile;

import com.qms.issuance.TicketResponse;
import com.qms.platform.Profiles;
import com.qms.platform.idempotency.IdempotencyService;
import com.qms.platform.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /remote-join/{serviceId}} (ticket 42, SRS §13.2, FR-MOB-010): a registered visitor's own remote join, the
 * mobile app's one issuance-like surface. {@code GET} shows the Service's policy and forfeit consequences before the
 * visitor commits (FR-MOB-023); {@code POST} joins, needing an {@code Idempotency-Key} exactly like every other
 * issuing endpoint (SRS §20.1), so a retry after a lost response cannot join twice.
 */
@RestController
@Profile(Profiles.SERVING)
public class RemoteJoinController {

    private static final String VISITOR = "hasRole('VISITOR')";

    private final RemoteJoinService service;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;

    RemoteJoinController(RemoteJoinService service, IdempotencyService idempotency, CurrentUser currentUser) {
        this.service = service;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
    }

    @PreAuthorize(VISITOR)
    @GetMapping("/remote-join/{serviceId}")
    public RemoteJoinViews.PolicyView policy(@PathVariable UUID serviceId) {
        return service.policy(serviceId);
    }

    @PreAuthorize(VISITOR)
    @PostMapping("/remote-join/{serviceId}")
    public ResponseEntity<TicketResponse> join(
            @PathVariable UUID serviceId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) RemoteJoinViews.JoinRequest request) {
        IdempotencyService.requireUsable(idempotencyKey);
        Double latitude = request == null ? null : request.latitude();
        Double longitude = request == null ? null : request.longitude();
        UUID visitorId = currentUser.require().userId();
        var result = idempotency.execute(
                "POST /remote-join/" + serviceId + ":" + visitorId,
                idempotencyKey,
                fingerprint(serviceId, visitorId),
                TicketResponse.class,
                () -> service.join(serviceId, latitude, longitude));
        return ResponseEntity.status(HttpStatus.CREATED).body(result.value());
    }

    /** The join is the visitor's own, once per key; a retry's slightly different GPS reading is still the same action. */
    private static String fingerprint(UUID serviceId, UUID visitorId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((serviceId + "|" + visitorId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
