package com.qms.reporting;

import com.qms.platform.Profiles;
import com.qms.platform.jobs.JobLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the retention purge sweep on one node of the cluster at a time (ADR-0010, ticket 53), the same {@link
 * JobLock} pattern {@code ReportExportJobScheduler} and {@code ReportScheduleScheduler} already use. Nightly is
 * enough resolution for a policy expressed in months; the schedule is a property so a test can turn it off
 * ({@code qms.retention.purge-cron=-}) and drive a sweep itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class RetentionPurgeScheduler {

    private final RetentionPurgeRunner runner;
    private final JobLock lock;

    RetentionPurgeScheduler(RetentionPurgeRunner runner, JobLock lock) {
        this.runner = runner;
        this.lock = lock;
    }

    @Scheduled(cron = "${qms.retention.purge-cron:0 30 2 * * *}", zone = "UTC")
    void tick() {
        lock.runExclusively("retention-purge", runner::tick);
    }
}
