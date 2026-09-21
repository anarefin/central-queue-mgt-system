package com.qms.reporting;

import com.qms.platform.Profiles;
import com.qms.platform.jobs.JobLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the nightly reporting extract on one node of the cluster at a time (ADR-0010, ticket 53), the same {@link
 * JobLock} pattern every other scheduled job in this package already uses. The schedule is a property so a test
 * can turn it off ({@code qms.reporting.extract.cron=-}) and drive a tick itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class ReportingExtractScheduler {

    private final ReportingExtractRunner runner;
    private final JobLock lock;

    ReportingExtractScheduler(ReportingExtractRunner runner, JobLock lock) {
        this.runner = runner;
        this.lock = lock;
    }

    @Scheduled(cron = "${qms.reporting.extract.cron:0 0 3 * * *}", zone = "UTC")
    void tick() {
        lock.runExclusively("reporting-extract", runner::tick);
    }
}
