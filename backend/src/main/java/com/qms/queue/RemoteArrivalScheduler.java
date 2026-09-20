package com.qms.queue;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Ticks {@link RemoteArrivalService}'s two sweeps (ticket 43, FR-MOB-020, FR-MOB-022): every node fires them, each
 * write inside is a single guarded statement, so whichever node's write lands first acts and the rest find nothing
 * (ADR-0010) — the same {@code @Scheduled} + guarded-update shape {@code AppointmentNoShowScheduler} already uses.
 * The schedule is a property so a test can turn it off ({@code qms.queue.remote-arrival-check-cron=-}) and drive
 * both sweeps itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class RemoteArrivalScheduler {

    private final RemoteArrivalService arrivals;

    RemoteArrivalScheduler(RemoteArrivalService arrivals) {
        this.arrivals = arrivals;
    }

    @Scheduled(cron = "${qms.queue.remote-arrival-check-cron:*/10 * * * * *}", zone = "UTC")
    void tick() {
        arrivals.sweepApproachingTurn();
        arrivals.sweepForfeits();
    }
}
