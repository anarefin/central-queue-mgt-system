package com.qms.dashboard;

import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps every active Site's live dashboard within its own {@code ≤ 5 s} staleness (FR-MON-001, NFR-PERF-004):
 * every node ticks the same sweep and each tick is a plain publish with no write of its own to race (ADR-0010), the
 * same {@code @Scheduled} shape {@link com.qms.queue.RemoteArrivalScheduler} already uses. The schedule is a
 * property so a test can turn it off ({@code qms.dashboard.refresh-cron=-}) and drive a tick itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
public class DashboardRefreshScheduler {

    private final DashboardReads reads;
    private final RealtimePublisher realtime;
    private final Clock clock;

    DashboardRefreshScheduler(DashboardReads reads, RealtimePublisher realtime, Clock clock) {
        this.reads = reads;
        this.realtime = realtime;
        this.clock = clock;
    }

    /** One sweep, public so a realtime test outside this package (the one place that can spin up a real server and a
     * real WebSocket, {@code com.qms.platform.realtime.RealServerSupport}) can drive it directly with the schedule off. */
    @Scheduled(cron = "${qms.dashboard.refresh-cron:*/4 * * * * *}", zone = "UTC")
    public void tick() {
        Instant now = clock.instant();
        for (UUID siteId : reads.activeSiteIds()) {
            realtime.publish(Topics.dashboard(siteId), "dashboard.refreshed", now, Map.of("site_id", siteId.toString()));
        }
    }
}
