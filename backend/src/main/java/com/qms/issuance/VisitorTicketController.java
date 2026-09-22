package com.qms.issuance;

import com.qms.feedback.FeedbackService;
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
    private final FeedbackService feedback;

    VisitorTicketController(TicketCredentialAccess access, VisitorTicketViews views, VisitorTicketActions actions, FeedbackService feedback) {
        this.access = access;
        this.views = views;
        this.actions = actions;
        this.feedback = feedback;
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

    /** Body: {@code {"granted": boolean, "consent_text_version": string?}} (FR-SEC-030, ticket 54). */
    public record RetentionConsentRequest(
            boolean granted, @com.fasterxml.jackson.annotation.JsonProperty("consent_text_version") String consentTextVersion) {}

    @PublicEndpoint("Anonymous visitor records their own consent for retention on their own visitor record (FR-SEC-030, §20.2)")
    @PostMapping("/tickets/{id}/visitor/retention-consent")
    public Map<String, Object> retentionConsent(
            @PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody RetentionConsentRequest request) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return actions.setRetentionConsent(ticket, request.granted(), request.consentTextVersion());
    }

    /** Body: the shape of a browser's own {@code PushSubscription.toJSON()} (ticket 39, §18.3, FR-INT-040). */
    public record PushSubscriptionRequest(String endpoint, Keys keys) {
        public record Keys(String p256dh, String auth) {}
    }

    @PublicEndpoint("Anonymous visitor registers their own device for Web Push on their own ticket (§18.3, FR-INT-040)")
    @PostMapping("/tickets/{id}/push-subscription")
    public Map<String, Object> pushSubscription(
            @PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody PushSubscriptionRequest request) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        String p256dh = request.keys() == null ? null : request.keys().p256dh();
        String auth = request.keys() == null ? null : request.keys().auth();
        return actions.subscribeWebPush(ticket, request.endpoint(), p256dh, auth);
    }

    /**
     * Body: {@code method} is {@code "qr"} (a site QR, the drift fallback) or {@code "geofence"} (validated against
     * the Site's own radius); {@code latitude}/{@code longitude} are required for {@code "geofence"} (ticket 43,
     * FR-MOB-021, FR-MOB-024).
     */
    public record CheckInRequest(String method, Double latitude, Double longitude) {}

    @PublicEndpoint("Anonymous visitor marks their own remote ticket present, by site QR or geofence (§19.1, FR-MOB-021)")
    @PostMapping("/tickets/{id}/check-in")
    public Map<String, Object> checkIn(
            @PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody(required = false) CheckInRequest request) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        String method = request == null ? null : request.method();
        Double latitude = request == null ? null : request.latitude();
        Double longitude = request == null ? null : request.longitude();
        return views.view(actions.checkIn(ticket, method, latitude, longitude));
    }

    @PublicEndpoint("Anonymous visitor asks once to be moved back on their own still-remote ticket (FR-MOB-031)")
    @PostMapping("/tickets/{id}/delay")
    public Map<String, Object> delay(@PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return views.view(actions.delay(ticket));
    }

    /** Body: {@code rating} (1-5, required) and an optional {@code comment} (ticket 45, FR-MOB-033). */
    public record FeedbackRequest(Integer rating, String comment) {}

    @PublicEndpoint("Anonymous visitor leaves optional feedback on their own completed ticket, once (FR-MOB-033, §20.2)")
    @PostMapping("/tickets/{id}/feedback")
    public Map<String, Object> feedback(
            @PathVariable UUID id, @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody FeedbackRequest request) {
        var ticket = access.verify(id, secret).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return feedback.submit(ticket.id(), request.rating(), request.comment());
    }
}
