package com.qms.appointment;

import com.qms.appointment.AppointmentBookingRepository.AppointmentRow;
import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a late check-in's appointment {@code no_show} in its own transaction (FR-ISS-033, §9.5, FR-APT-040,
 * FR-APT-041). {@link AppointmentCheckInService#checkIn} calls this and then throws the refusal that reports it to
 * the caller; a plain {@code @Transactional} write in that same method would be undone by Spring's default
 * rollback-on-exception the instant that refusal is thrown, silently losing the very state change FR-ISS-033 asks
 * for. {@code REQUIRES_NEW} — reachable only through this separate bean, since a same-class call would bypass the
 * proxy that makes it a real, independent transaction — commits the mark before the caller's own transaction (which
 * wrote nothing of its own on this path) rolls back around the thrown refusal.
 */
@Component
@Profile(Profiles.SERVING)
class AppointmentNoShowMarker {

    private final AppointmentBookingRepository repository;
    private final AuditWriter audit;

    AppointmentNoShowMarker(AppointmentBookingRepository repository, AuditWriter audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markAndAudit(AppointmentRow appointment, Instant now) {
        if (repository.markNoShow(appointment.id(), now) == 1) {
            audit.record(AuditEvent.of("appointment.no_show", "appointment", appointment.id())
                    .withBefore(Map.of("reference_code", appointment.referenceCode(), "state", "booked")));
        }
    }
}
