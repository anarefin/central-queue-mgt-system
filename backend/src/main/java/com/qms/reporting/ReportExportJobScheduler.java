package com.qms.reporting;

import com.qms.platform.Profiles;
import com.qms.platform.jobs.JobLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the background export job worker on one node of the cluster at a time (ADR-0010, ticket 49), the same
 * {@link JobLock} pattern {@code NotificationSendScheduler} already uses. The schedule is a property so a test can
 * turn it off ({@code qms.reporting.export.poll-cron=-}) and drive a sweep itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class ReportExportJobScheduler {

    private final ReportExportJobWorker worker;
    private final JobLock lock;

    ReportExportJobScheduler(ReportExportJobWorker worker, JobLock lock) {
        this.worker = worker;
        this.lock = lock;
    }

    @Scheduled(cron = "${qms.reporting.export.poll-cron:*/3 * * * * *}", zone = "UTC")
    void tick() {
        lock.runExclusively("report-export-job", worker::tick);
    }
}
