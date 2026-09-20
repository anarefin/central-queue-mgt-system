package com.qms.appointment;

import com.qms.appointment.AppointmentBookingViews.AppointmentResponse;
import com.qms.appointment.AppointmentBookingViews.BookAppointmentRequest;
import com.qms.appointment.AppointmentBookingViews.CancelAppointmentRequest;
import com.qms.appointment.AppointmentBookingViews.RescheduleAppointmentRequest;
import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /appointments} (SRS §9.2, FR-APT-011..016): reception's booking screen. {@code PATCH}/{@code DELETE
 * /appointments/{id}} (§9.3, FR-APT-020..023): reschedule and cancel. Every permission is repeated here (API-016);
 * the service checks it again.
 */
@RestController
@Profile(Profiles.SERVING)
public class AppointmentBookingController {

    private final AppointmentBookingService booking;

    AppointmentBookingController(AppointmentBookingService booking) {
        this.booking = booking;
    }

    @PreAuthorize(AppointmentBookingService.BOOK)
    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse book(@RequestBody(required = false) BookAppointmentRequest request) {
        return booking.book(request);
    }

    @PreAuthorize(AppointmentBookingService.BOOK)
    @PatchMapping("/appointments/{id}")
    public AppointmentResponse reschedule(@PathVariable UUID id, @RequestBody(required = false) RescheduleAppointmentRequest request) {
        return booking.reschedule(id, request);
    }

    @PreAuthorize(AppointmentBookingService.BOOK)
    @DeleteMapping("/appointments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, @RequestBody(required = false) CancelAppointmentRequest request) {
        booking.cancel(id, request);
    }
}
