package com.qms.dashboard;

import com.qms.platform.Profiles;
import com.qms.platform.alerts.ThresholdAlertContext;
import com.qms.platform.alerts.ThresholdAlertRaiser;
import com.qms.platform.alerts.ThresholdAlertTypes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sweeps every Service with at least one configured threshold (FR-MON-020) and reports a breach through {@link
 * ThresholdAlertRaiser}, which owns grouping (FR-MON-023), notifying and the realtime event. Every node ticks the
 * same sweep; a breach that persists across ticks is naturally grouped into the same still-open alert rather than
 * re-raised, the same {@code @Scheduled} shape {@link DashboardRefreshScheduler} already uses. The schedule is a
 * property so a test can turn it off ({@code qms.alerts.threshold-check-cron=-}) and drive a tick itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
public class ThresholdAlertScheduler {

    private final AlertThresholdRepository thresholds;
    private final ThresholdAlertReads reads;
    private final ThresholdAlertRaiser raiser;
    private final Clock clock;

    ThresholdAlertScheduler(AlertThresholdRepository thresholds, ThresholdAlertReads reads, ThresholdAlertRaiser raiser, Clock clock) {
        this.thresholds = thresholds;
        this.reads = reads;
        this.raiser = raiser;
        this.clock = clock;
    }

    @Scheduled(cron = "${qms.alerts.threshold-check-cron:*/30 * * * * *}", zone = "UTC")
    public void tick() {
        Instant now = clock.instant();
        for (UUID serviceId : thresholds.configuredServiceIds()) {
            try {
                thresholds.find(serviceId).ifPresent(threshold -> evaluate(serviceId, threshold, now));
            } catch (RuntimeException e) {
                // one Service's failure must not stop the sweep for the rest
                org.slf4j.LoggerFactory.getLogger(ThresholdAlertScheduler.class).error("threshold evaluation failed for service {}", serviceId, e);
            }
        }
    }

    private void evaluate(UUID serviceId, AlertThreshold threshold, Instant now) {
        ThresholdAlertReads.ServiceSite site = reads.site(serviceId);
        if (site == null) return; // the Service (or its Site) is gone since the sweep started

        if (threshold.queueLengthMax() != null) {
            int length = reads.queueLength(serviceId);
            if (length > threshold.queueLengthMax()) {
                raise(site.siteId(), serviceId, ThresholdAlertTypes.QUEUE_LENGTH, BigDecimal.valueOf(length), BigDecimal.valueOf(threshold.queueLengthMax()), now);
            }
        }
        if (threshold.longestWaitMinutesMax() != null) {
            long waitMinutes = reads.longestWaitSeconds(serviceId, now) / 60;
            if (waitMinutes > threshold.longestWaitMinutesMax()) {
                raise(site.siteId(), serviceId, ThresholdAlertTypes.LONGEST_WAIT, BigDecimal.valueOf(waitMinutes), BigDecimal.valueOf(threshold.longestWaitMinutesMax()), now);
            }
        }
        if (threshold.idleCountersWithQueueMax() != null) {
            int idle = reads.idleCountersWithQueue(serviceId);
            if (idle > threshold.idleCountersWithQueueMax()) {
                raise(site.siteId(), serviceId, ThresholdAlertTypes.IDLE_COUNTERS, BigDecimal.valueOf(idle), BigDecimal.valueOf(threshold.idleCountersWithQueueMax()), now);
            }
        }
        if (threshold.noShowRatePercentMax() != null) {
            Instant todayStart = todayStart(now, site.timezone());
            BigDecimal rate = reads.noShowRatePercent(serviceId, todayStart);
            if (rate != null && rate.compareTo(threshold.noShowRatePercentMax()) > 0) {
                raise(site.siteId(), serviceId, ThresholdAlertTypes.NO_SHOW_RATE, rate, threshold.noShowRatePercentMax(), now);
            }
        }
        if (threshold.deviceOfflineMinutesMax() != null) {
            long offlineMinutes = reads.maxDeviceOfflineMinutes(site.siteId(), now);
            if (offlineMinutes > threshold.deviceOfflineMinutesMax()) {
                raise(site.siteId(), serviceId, ThresholdAlertTypes.DEVICE_OFFLINE, BigDecimal.valueOf(offlineMinutes), BigDecimal.valueOf(threshold.deviceOfflineMinutesMax()), now);
            }
        }
    }

    private static Instant todayStart(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
    }

    private void raise(UUID siteId, UUID serviceId, String thresholdType, BigDecimal measured, BigDecimal limit, Instant now) {
        raiser.raise(new ThresholdAlertContext(siteId, serviceId, thresholdType, null, measured, limit, now));
    }
}
