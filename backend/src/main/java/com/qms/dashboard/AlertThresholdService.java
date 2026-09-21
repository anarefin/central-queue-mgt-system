package com.qms.dashboard;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-Service alert thresholds (SRS §15.4, FR-MON-020): reuses {@code config:service_catalogue} the same way
 * {@code com.qms.notification.NotificationTriggerConfigService} does for its own per-Service settings, since
 * neither has a row of its own in the SRS §5.2 matrix beyond "manage the service catalogue" — configuring what
 * counts as trouble for a Service is that same act.
 */
@Service
public class AlertThresholdService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final AlertThresholdRepository repository;
    private final ScopeGuard scope;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;
    private final Clock clock;

    AlertThresholdService(AlertThresholdRepository repository, ScopeGuard scope, JdbcTemplate jdbc, AuditWriter audit, Clock clock) {
        this.repository = repository;
        this.scope = scope;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public AlertThreshold get(UUID serviceId) {
        requireService(serviceId);
        return repository.find(serviceId).orElseGet(() -> AlertThreshold.empty(serviceId));
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public AlertThreshold set(UUID serviceId, AlertThresholdRequest request, UUID actorId) {
        requireService(serviceId);
        validate(request);
        AlertThreshold before = repository.find(serviceId).orElseGet(() -> AlertThreshold.empty(serviceId));
        repository.upsert(serviceId, request, actorId, clock.instant());
        AlertThreshold after = repository.find(serviceId).orElseThrow();
        audit.record(AuditEvent.of("alert_threshold.updated", "service", serviceId).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    private void validate(AlertThresholdRequest request) {
        BigDecimal rate = request.noShowRatePercentMax();
        boolean invalid = negative(request.queueLengthMax())
                || negative(request.longestWaitMinutesMax())
                || negative(request.idleCountersWithQueueMax())
                || negative(request.deviceOfflineMinutesMax())
                || (request.groupWindowMinutes() != null && request.groupWindowMinutes() < 1)
                || negative(request.escalationDelayMinutes())
                || (rate != null && (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0));
        if (invalid) throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", java.util.List.of(Map.of("field", "threshold", "code", "invalid"))));
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }

    /** The Site a Service belongs to, so a Service outside the caller's own Site scope is refused (FR-CFG-106). */
    private void requireService(UUID serviceId) {
        UUID siteId = jdbc.query(
                "SELECT g.site_id FROM service s JOIN service_group g ON g.id = s.service_group_id WHERE s.id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                serviceId);
        if (siteId == null) throw new ApiException(ErrorCode.NOT_FOUND);
        scope.requireSite(siteId);
    }

    private static Map<String, Object> snapshot(AlertThreshold t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("queue_length_max", t.queueLengthMax());
        values.put("longest_wait_minutes_max", t.longestWaitMinutesMax());
        values.put("idle_counters_with_queue_max", t.idleCountersWithQueueMax());
        values.put("no_show_rate_percent_max", t.noShowRatePercentMax());
        values.put("device_offline_minutes_max", t.deviceOfflineMinutesMax());
        values.put("group_window_minutes", t.groupWindowMinutes());
        values.put("escalation_delay_minutes", t.escalationDelayMinutes());
        return values;
    }
}
