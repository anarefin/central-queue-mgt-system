package com.qms.appointment;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends every appointment reminder due at one of {@code qms.appointment.reminder-offsets-minutes} (FR-APT-050,
 * default 24 hours and 1 hour before the slot). Every node fires it; {@link
 * AppointmentBookingService#sendDueReminders()} claims each (appointment, offset) pair it sends through {@code
 * appointment_reminder_sent}'s unique constraint, so whichever node's insert lands first sends it and the rest find
 * nothing (ADR-0010) — the same pattern {@link AppointmentNoShowScheduler} and {@link AppointmentHoldExpiryScheduler}
 * already use for their own sweeps. The schedule is a property so a test can turn it off ({@code
 * qms.appointment.reminder-check-cron=-}) and drive the sweep itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class AppointmentReminderScheduler {

    private final AppointmentBookingService booking;

    AppointmentReminderScheduler(AppointmentBookingService booking) {
        this.booking = booking;
    }

    @Scheduled(cron = "${qms.appointment.reminder-check-cron:*/5 * * * * *}", zone = "UTC")
    void tick() {
        booking.sendDueReminders();
    }
}
