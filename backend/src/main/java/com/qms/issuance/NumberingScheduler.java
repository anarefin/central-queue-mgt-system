package com.qms.issuance;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Triggers the scheduled numbering resets (FR-CFG-019) once a minute. Every node fires it; {@link NumberingResets}
 * takes the cluster-wide lock, so one node does the work and the rest skip (ADR-0010). The schedule is a property so a
 * test can switch it off with {@code qms.numbering.scheduler.cron=-} and drive {@link #tick()} itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class NumberingScheduler {

    private final NumberingResets resets;

    NumberingScheduler(NumberingResets resets) {
        this.resets = resets;
    }

    @Scheduled(cron = "${qms.numbering.scheduler.cron:0 * * * * *}", zone = "UTC")
    void tick() {
        resets.runScheduled();
    }
}
