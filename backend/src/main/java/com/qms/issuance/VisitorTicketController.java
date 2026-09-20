package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.PublicEndpoint;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The visitor ticket page's own REST surface (SRS §20.2, §20.4, ticket 37): anonymous, by ticket id plus the
 * {@code X-Ticket-Secret} header, read-only on that one ticket (FR-SEC-033). A wrong or missing secret answers
 * {@code unauthenticated} exactly like a bad staff token, never {@code not_found}, so a guess cannot tell an unknown
 * ticket id from a wrong secret on a real one.
 */
@RestController
@Profile(Profiles.SERVING)
class VisitorTicketController {

    static final String SECRET_HEADER = "X-Ticket-Secret";

    private final TicketCredentialAccess access;
    private final VisitorTicketViews views;
    private final VisitorTicketActions actions;

    VisitorTicketController(TicketCredentialAccess access, VisitorTicketViews views, VisitorTicketActions actions) {
        this.access = access;
        this.views = views;
        this.actions = actions;
    }

    @PublicEndpoint("Anonymous visitor reads only their own ticket, by id plus its secret (§20.2, FR-SEC-033)")
    @GetMapping("/tickets/{id}/visitor")
    public Map<String, Object> view(@PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return views.view(ticket);
    }

    @PublicEndpoint("Anonymous visitor cancels only their own ticket, before being called (FR-MOB-030, §20.2)")
    @PostMapping("/tickets/{id}/visitor-cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return views.view(actions.cancel(ticket));
    }

    /** Body: {@code {"opted_out": boolean, "consent_text_version": string?}} (FR-NTF-035, FR-SEC-030, ticket 38). */
    public record NotificationOptOutRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("opted_out") boolean optedOut,
            @com.fasterxml.jackson.annotation.JsonProperty("consent_text_version") String consentTextVersion) {}

    @PublicEndpoint("Anonymous visitor opts their own visitor record out of non-essential notifications (FR-NTF-035, §20.2)")
    @PostMapping("/tickets/{id}/visitor/notification-opt-out")
    public Map<String, Object> notificationOptOut(
            @PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody NotificationOptOutRequest request) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return actions.setNotificationOptOut(ticket, request.optedOut(), request.consentTextVersion());
    }
}
