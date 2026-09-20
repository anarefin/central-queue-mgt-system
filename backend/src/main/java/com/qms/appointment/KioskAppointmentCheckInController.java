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
 * {@code POST /kiosk/appointments/check-in} (SRS §8.4, FR-ISS-030): a paired kiosk device checks a visitor in by
 * typed code or camera QR. Authorised the same way as {@link com.qms.issuance.KioskTicketController}: {@code
 * hasRole('KIOSK')}, not a human permission, so unlike {@link AppointmentCheckInService#CHECKIN} it is not repeated
 * on the service method.
 */
@RestController
@Profile(Profiles.SERVING)
public class KioskAppointmentCheckInController {

    private static final String CHECKIN = "hasRole('KIOSK')";

    private final AppointmentCheckInService checkIn;

    KioskAppointmentCheckInController(AppointmentCheckInService checkIn) {
        this.checkIn = checkIn;
    }

    @PreAuthorize(CHECKIN)
    @PostMapping("/kiosk/appointments/check-in")
    public CheckInResponse checkIn(@RequestBody(required = false) CheckInRequest request) {
        return checkIn.checkInAsKiosk(request);
    }
}
