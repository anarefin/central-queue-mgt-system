package com.qms.notification;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A visitor's own device opting into Web Push for one ticket (ticket 39, §18.3, FR-INT-040): called from the
 * visitor ticket page's own ticket-secret credential (ticket 37's {@code com.qms.issuance.VisitorTicketController}),
 * never a bearer token — the same anonymous, ticket-scoped access as the page's read and cancel.
 */
@Service
public class WebPushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final WebPushProperties properties;
    private final Clock clock;

    WebPushSubscriptionService(PushSubscriptionRepository repository, WebPushProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void subscribe(UUID ticketId, UUID visitorId, String endpoint, String p256dh, String auth) {
        requireField("endpoint", endpoint);
        requireField("p256dh", p256dh);
        requireField("auth", auth);
        try {
            PushEndpointSecurity.requireSafe(endpoint, properties.allowInsecureEndpointsForTests());
        } catch (PushEndpointSecurity.UnsafeEndpointException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "endpoint", "code", "unsafe_endpoint"))));
        }
        repository.upsert(ticketId, visitorId, endpoint, p256dh, auth, clock.instant());
    }

    private static void requireField(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", "required"))));
        }
    }
}
