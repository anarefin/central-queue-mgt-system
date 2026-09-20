package com.qms.appointment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param holdMinutes how long a slot stays held while booking details are completed before it is released
 *     (FR-APT-012, default 5 minutes)
 * @param maxActivePerVisitor the most active appointments (held, booked, rescheduled or checked in) one visitor may
 *     have at once, across every Service (FR-APT-016, default 3)
 * @param holdExpiryCheckCron when {@link AppointmentHoldExpiryScheduler} sweeps expired holds; {@code -} disables it
 *     so a test can drive the sweep itself, the same convention as {@code qms.queue.call-timeout-check-cron}
 */
@ConfigurationProperties("qms.appointment")
public record AppointmentProperties(
        @DefaultValue("5") int holdMinutes, @DefaultValue("3") int maxActivePerVisitor, @DefaultValue("*/5 * * * * *") String holdExpiryCheckCron) {}
