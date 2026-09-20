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
 * @param visitorCutoffMinutes how close to the slot a visitor may still reschedule or cancel; a staff caller may act
 *     any closer, but must then give a reason (FR-APT-020, default 2 hours)
 * @param waitlistHoldMinutes how long a freed slot is held for the first waitlisted visitor before it would be swept
 *     the same way any other unclaimed hold is (FR-APT-023); the SRS gives no default, so this is set shorter than
 *     {@code visitorCutoffMinutes} so an unclaimed offer can still be re-offered well before the slot itself passes
 * @param checkinWindowBeforeMinutes how early before the slot check-in is allowed (FR-ISS-031, default 30 minutes)
 * @param checkinGraceMinutes how late after the slot check-in is still allowed, and the same grace period past which
 *     an appointment is a no-show (FR-ISS-031, FR-ISS-033, FR-APT-040, default 15 minutes)
 */
@ConfigurationProperties("qms.appointment")
public record AppointmentProperties(
        @DefaultValue("5") int holdMinutes,
        @DefaultValue("3") int maxActivePerVisitor,
        @DefaultValue("*/5 * * * * *") String holdExpiryCheckCron,
        @DefaultValue("120") int visitorCutoffMinutes,
        @DefaultValue("30") int waitlistHoldMinutes,
        @DefaultValue("30") int checkinWindowBeforeMinutes,
        @DefaultValue("15") int checkinGraceMinutes) {}
