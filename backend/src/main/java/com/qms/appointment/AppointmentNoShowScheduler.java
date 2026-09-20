package com.qms.appointment;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Marks every appointment nobody ever checked in for {@code no_show} once its slot plus grace period has passed
 * (FR-APT-040, §19.2 {@code booked -> no_show: grace elapsed}), freeing its capacity immediately (FR-APT-041). Every
 * node fires it; {@link AppointmentBookingService#markOverdueNoShows()} updates each row guarded on {@code state =
 * 'booked'}, so whichever node's write lands first marks it and the rest find nothing (ADR-0010) — the same pattern
 * {@link AppointmentHoldExpiryScheduler} already uses for its own sweep. The schedule is a property so a test can
 * turn it off ({@code qms.appointment.no-show-check-cron=-}) and drive the sweep itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class AppointmentNoShowScheduler {

    private final AppointmentBookingService booking;

    AppointmentNoShowScheduler(AppointmentBookingService booking) {
        this.booking = booking;
    }

    @Scheduled(cron = "${qms.appointment.no-show-check-cron:*/5 * * * * *}", zone = "UTC")
    void tick() {
        booking.markOverdueNoShows();
    }
}
