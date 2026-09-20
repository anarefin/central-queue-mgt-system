package com.qms.appointment;

import com.qms.appointment.AppointmentCheckInViews.CheckInRequest;
import com.qms.appointment.AppointmentCheckInViews.CheckInResponse;
import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /appointments/check-in} (SRS §8.4, FR-ISS-030): reception's check-in action. Every permission is
 * repeated here (API-016), the same way {@link AppointmentBookingController} repeats {@code AppointmentBookingService.BOOK};
 * the service checks it again.
 */
@RestController
@Profile(Profiles.SERVING)
public class AppointmentCheckInController {

    private final AppointmentCheckInService checkIn;

    AppointmentCheckInController(AppointmentCheckInService checkIn) {
        this.checkIn = checkIn;
    }

    @PreAuthorize(AppointmentCheckInService.CHECKIN)
    @PostMapping("/appointments/check-in")
    public CheckInResponse checkIn(@RequestBody(required = false) CheckInRequest request) {
        return checkIn.checkInAsStaff(request);
    }
}
