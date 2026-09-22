package com.qms.integration.webhook;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The webhook delivery log and its replay (ticket 57, FR-INT-021). Permission is also enforced in
 * {@link WebhookDeliveryLogService} (API-016). */
@RestController
@Profile(Profiles.SERVING)
public class WebhookDeliveryController {

    private final WebhookDeliveryLogService log;

    WebhookDeliveryController(WebhookDeliveryLogService log) {
        this.log = log;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    @GetMapping("/webhook-deliveries")
    public Items<WebhookDeliveryWithAttempts> list(
            @RequestParam(name = "endpoint_id", required = false) UUID endpointId,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return new Items<>(log.search(endpointId, eventType, status, limit));
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @PostMapping("/webhook-deliveries/{id}/replay")
    public WebhookDeliveryWithAttempts replay(@PathVariable UUID id) {
        return log.replay(id);
    }
}
