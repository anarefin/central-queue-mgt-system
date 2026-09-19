package com.qms.session;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfer over HTTP (SRS §20.4). It lives with the counter session because a transfer is an action on the ticket a session is
 * serving, and the service checks ownership and scope again (API-016, FR-CFG-105). The ticket's version goes in {@code If-Match}.
 */
@RestController
@Profile(Profiles.SERVING)
public class TransferController {

    private final SessionService service;

    TransferController(SessionService service) {
        this.service = service;
    }

    /** F7: closes the ticket in service as {@code transferred} and creates its successor in the target queue (FR-QUE-052, ADR-0006). */
    @PreAuthorize(SessionService.TRANSFER)
    @PostMapping("/tickets/{id}/transfer")
    public TransferResponse transfer(
            @PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody(required = false) TransferRequest request) {
        return service.transfer(id, request, SessionController.version(ifMatch));
    }

    /** The Services, counters and agents the ticket in service may be transferred to. */
    @PreAuthorize(SessionService.TRANSFER)
    @GetMapping("/sessions/{id}/transfer-targets")
    public TransferTargets targets(@PathVariable UUID id) {
        return service.transferTargets(id);
    }
}
