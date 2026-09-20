package com.qms.appointment;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Releases any slot hold that outlived its window (FR-APT-012, §19.2 {@code held_slot -> [*]: hold expired}) every
 * few seconds. Every node fires it; {@link AppointmentBookingService#releaseExpiredHolds()} deletes each hold in the
 * database, so whichever node's delete lands first releases it and the rest find nothing (ADR-0010) — the same
 * pattern {@code CallTimeoutScheduler} uses. The schedule is a property so a test can turn it off
 * ({@code qms.appointment.hold-expiry-check-cron=-}) and drive the sweep itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class AppointmentHoldExpiryScheduler {

    private final AppointmentBookingService booking;

    AppointmentHoldExpiryScheduler(AppointmentBookingService booking) {
        this.booking = booking;
    }

    @Scheduled(cron = "${qms.appointment.hold-expiry-check-cron:*/5 * * * * *}", zone = "UTC")
    void tick() {
        booking.releaseExpiredHolds();
    }
}
