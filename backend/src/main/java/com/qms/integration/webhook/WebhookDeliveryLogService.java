package com.qms.integration.webhook;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.integration.webhook.WebhookDeliveryRepository.DeliveryRow;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin delivery log and its replay (FR-INT-021): every delivery, filterable by endpoint, event type and
 * status, with its attempts. Reuses {@code audit:read} for the read side — an org- or system-wide, read-only view of
 * what the system did, the same shape {@code notification.NotificationLogService} already reuses it for (ticket 38);
 * replaying a delivery is a write with a real side effect (a fresh POST to an external system) and needs the same
 * {@code config:org_sites_zones} permission managing the endpoint itself does.
 */
@Service
public class WebhookDeliveryLogService {

    private static final int DEFAULT_LIMIT = 100;

    private final WebhookDeliveryRepository deliveries;
    private final AuditWriter audit;
    private final Clock clock;

    WebhookDeliveryLogService(WebhookDeliveryRepository deliveries, AuditWriter audit, Clock clock) {
        this.deliveries = deliveries;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    public List<WebhookDeliveryWithAttempts> search(UUID endpointId, String eventType, String status, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
        return deliveries.forAdmin(endpointId, eventType, status, effectiveLimit).stream()
                .map(row -> new WebhookDeliveryWithAttempts(row, deliveries.attemptsOf(row.id())))
                .toList();
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @Transactional
    public WebhookDeliveryWithAttempts replay(UUID id) {
        DeliveryRow before = deliveries.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        deliveries.resetForReplay(id, clock.instant());
        audit.record(AuditEvent.of("webhook_delivery.replayed", "webhook_delivery", id).withBefore(Map.of("status", before.status())));
        DeliveryRow after = deliveries.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new WebhookDeliveryWithAttempts(after, deliveries.attemptsOf(id));
    }
}
