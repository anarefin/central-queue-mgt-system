package com.qms.reporting;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the reporting store within {@code ≤ 60 s} of the live transactional tables (FR-RPT-020, §16.3): every node
 * ticks the same idempotent sweep (the same {@code @Scheduled} shape {@code com.qms.dashboard.DashboardRefreshScheduler}
 * already uses, ADR-0010), well inside the 60 s budget so a slow node or a missed tick still leaves margin. The
 * schedule is a property so a test can turn it off ({@code qms.reporting.refresh-cron=-}) and drive a tick itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
public class ReportingRefreshScheduler {

    private final ReportingRefreshService service;

    ReportingRefreshScheduler(ReportingRefreshService service) {
        this.service = service;
    }

    /** One sweep, public so a test can drive it directly with the schedule off. */
    @Scheduled(cron = "${qms.reporting.refresh-cron:*/15 * * * * *}", zone = "UTC")
    public void tick() {
        service.refresh();
    }
}
