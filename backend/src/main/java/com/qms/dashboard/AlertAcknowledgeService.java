package com.qms.dashboard;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acknowledging a threshold alert with an optional note (FR-MON-022). Kept apart from {@link AlertReadService} for
 * the reason its own javadoc explains: this class needs {@code RealtimePublisher}, which {@link AlertTopics} (one
 * of the beans the hub itself collects) must never end up depending on.
 */
@Service
public class AlertAcknowledgeService {

    private static final int MAX_NOTE_LENGTH = 1000;

    private final AlertRepository repository;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final RealtimePublisher realtime;
    private final AuditWriter audit;
    private final Clock clock;

    AlertAcknowledgeService(AlertRepository repository, CurrentUser currentUser, ScopeGuard scope, RealtimePublisher realtime, AuditWriter audit, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.scope = scope;
        this.realtime = realtime;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(AlertReadService.VIEW_OR_ACT)
    @Transactional
    public Alert acknowledge(UUID id, String note) {
        Alert alert = repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(alert.siteId());
        UUID alertGroup = repository.groupOfService(alert.serviceId());
        if (alertGroup != null) scope.requireGroup(alertGroup);
        if (Alert.ACKNOWLEDGED.equals(alert.state())) return alert;
        String trimmed = note == null ? null : note.strip();
        if (trimmed != null && trimmed.isEmpty()) trimmed = null;
        if (trimmed != null && trimmed.length() > MAX_NOTE_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "note", "code", "too_long"))));
        }
        UUID actorId = currentUser.require().userId();
        Instant now = clock.instant();
        repository.acknowledge(id, now, actorId, trimmed);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alert_id", id.toString());
        data.put("site_id", alert.siteId().toString());
        data.put("acknowledged_by", actorId.toString());
        data.put("note", trimmed);
        realtime.publish(Topics.alerts(alert.siteId()), "alert.acknowledged", now, data);
        audit.record(AuditEvent.of("alert.acknowledged", "alert", id).withAfter(data));

        return repository.find(id).orElseThrow();
    }
}
