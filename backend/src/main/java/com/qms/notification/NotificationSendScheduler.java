package com.qms.notification;

import com.qms.platform.Profiles;
import com.qms.platform.jobs.JobLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the notification job worker on one node of the cluster at a time (ADR-0010, FR-NTF-003), the same
 * {@link JobLock} pattern {@code AppointmentNoShowScheduler} already uses. The schedule is a property so a test can
 * turn it off ({@code qms.notification.send-poll-cron=-}) and drive a sweep itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class NotificationSendScheduler {

    private final NotificationSendWorker worker;
    private final JobLock lock;

    NotificationSendScheduler(NotificationSendWorker worker, JobLock lock) {
        this.worker = worker;
        this.lock = lock;
    }

    @Scheduled(cron = "${qms.notification.send-poll-cron:*/5 * * * * *}", zone = "UTC")
    void tick() {
        lock.runExclusively("notification-send", worker::tick);
    }
}
