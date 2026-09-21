package com.qms.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AlertThresholdRepository {

    private final JdbcTemplate jdbc;

    AlertThresholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AlertThreshold> find(UUID serviceId) {
        return jdbc.query("SELECT * FROM service_alert_threshold WHERE service_id = ?", this::map, serviceId).stream().findFirst();
    }

    void upsert(UUID serviceId, AlertThresholdRequest request, UUID actorId, Instant now) {
        int updated = jdbc.update(
                "UPDATE service_alert_threshold SET queue_length_max = ?, longest_wait_minutes_max = ?, idle_counters_with_queue_max = ?,"
                        + " no_show_rate_percent_max = ?, device_offline_minutes_max = ?, group_window_minutes = ?, escalation_delay_minutes = ?,"
                        + " updated_at = ?, updated_by = ? WHERE service_id = ?",
                request.queueLengthMax(), request.longestWaitMinutesMax(), request.idleCountersWithQueueMax(), request.noShowRatePercentMax(),
                request.deviceOfflineMinutesMax(), request.groupWindowMinutes(), request.escalationDelayMinutes(), ts(now), actorId, serviceId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO service_alert_threshold (service_id, queue_length_max, longest_wait_minutes_max, idle_counters_with_queue_max,"
                            + " no_show_rate_percent_max, device_offline_minutes_max, group_window_minutes, escalation_delay_minutes, updated_at, updated_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    serviceId, request.queueLengthMax(), request.longestWaitMinutesMax(), request.idleCountersWithQueueMax(),
                    request.noShowRatePercentMax(), request.deviceOfflineMinutesMax(), request.groupWindowMinutes(), request.escalationDelayMinutes(),
                    ts(now), actorId);
        }
    }

    /** Every Service with at least one metric configured, for {@link ThresholdAlertScheduler}'s own sweep. */
    java.util.List<UUID> configuredServiceIds() {
        return jdbc.queryForList(
                "SELECT service_id FROM service_alert_threshold WHERE queue_length_max IS NOT NULL OR longest_wait_minutes_max IS NOT NULL"
                        + " OR idle_counters_with_queue_max IS NOT NULL OR no_show_rate_percent_max IS NOT NULL OR device_offline_minutes_max IS NOT NULL",
                UUID.class);
    }

    private AlertThreshold map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AlertThreshold(
                rs.getObject("service_id", UUID.class),
                rs.getObject("queue_length_max", Integer.class),
                rs.getObject("longest_wait_minutes_max", Integer.class),
                rs.getObject("idle_counters_with_queue_max", Integer.class),
                rs.getObject("no_show_rate_percent_max", BigDecimal.class),
                rs.getObject("device_offline_minutes_max", Integer.class),
                rs.getObject("group_window_minutes", Integer.class),
                rs.getObject("escalation_delay_minutes", Integer.class),
                rs.getObject("updated_at", java.sql.Timestamp.class) == null ? null : rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("updated_by", UUID.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
