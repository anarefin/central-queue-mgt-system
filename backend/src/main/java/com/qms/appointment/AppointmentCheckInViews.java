package com.qms.appointment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.issuance.TicketResponse;
import java.util.UUID;

/** What {@code POST /appointments/check-in} and {@code POST /kiosk/appointments/check-in} send and receive (SRS §8.4, ticket 35). */
public final class AppointmentCheckInViews {

    private AppointmentCheckInViews() {}

    /** The reference code a visitor presents, typed, scanned from a QR, or read out at reception (FR-ISS-030). */
    public record CheckInRequest(@JsonProperty("reference_code") String referenceCode) {}

    /**
     * The result of a check-in attempt (FR-ISS-030..033, FR-APT-030..032). {@code outcome} is {@code "converted"}
     * when the appointment moved {@code checked_in -> converted} and {@code ticket} is its new Ticket (§19.2), or
     * {@code "walk_in"} when arrival was early enough that a walk-in Ticket was offered instead, leaving the
     * appointment still {@code booked} (FR-ISS-032). A late arrival past the grace period is refused outright
     * (§20.3 {@code conflict}, reason {@code no_show}) rather than returned here.
     */
    public record CheckInResponse(
            @JsonProperty("appointment_id") UUID appointmentId,
            @JsonProperty("reference_code") String referenceCode,
            String outcome,
            @JsonProperty("appointment_state") String appointmentState,
            TicketResponse ticket) {}
}
