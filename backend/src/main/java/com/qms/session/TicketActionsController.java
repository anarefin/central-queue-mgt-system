package com.qms.session;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff actions on a ticket over HTTP (SRS §20.4): change its Priority class and cancel it. The service checks the permission,
 * ownership and scope again (API-016, FR-CFG-105). The ticket's version may go in {@code If-Match}.
 */
@RestController
@Profile(Profiles.SERVING)
public class TicketActionsController {

    private final TicketActions actions;

    TicketActionsController(TicketActions actions) {
        this.actions = actions;
    }

    /** Changes the class of a waiting ticket, with a mandatory reason (FR-QUE-012). */
    @PreAuthorize(TicketActions.REPRIORITISE)
    @PostMapping("/tickets/{id}/priority")
    public TicketChange reprioritise(
            @PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody(required = false) ReprioritiseRequest request) {
        return actions.reprioritise(id, request, SessionController.version(ifMatch));
    }

    /** Cancels an active ticket (§19.1); an agent may cancel only their own. */
    @PreAuthorize(TicketActions.CANCEL)
    @PostMapping("/tickets/{id}/cancel")
    public TicketChange cancel(
            @PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody(required = false) CancelTicketRequest request) {
        return actions.cancel(id, request, SessionController.version(ifMatch));
    }
}
