package com.qms.appointment;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** What {@code POST /appointments} sends and receives (FR-APT-013..016). Dates are {@code yyyy-MM-dd}, times {@code HH:mm}, in the Site's time zone. */
public final class AppointmentBookingViews {

    private AppointmentBookingViews() {}

    /**
     * A staff booking (FR-APT-013, FR-APT-015): either an existing {@code visitor_id} (from the directory) or a
     * minimal {@code contact_name}/{@code contact_phone} (and optional {@code contact_email}) for a visitor not yet
     * known, the same two ways Reception already captures a visitor for a walk-in ticket.
     */
    public record BookAppointmentRequest(
            @JsonProperty("service_id") UUID serviceId,
            String date,
            String start,
            String end,
            String source,
            @JsonProperty("visitor_id") UUID visitorId,
            @JsonProperty("contact_name") String contactName,
            @JsonProperty("contact_phone") String contactPhone,
            @JsonProperty("contact_email") String contactEmail,
            @JsonProperty("preferred_agent_id") UUID preferredAgentId,
            @JsonProperty("purpose_note") String purposeNote,
            String language) {}

    /** The booked appointment (FR-APT-014): {@code reference_code} is what the visitor is given, and what a QR the caller renders should encode. */
    public record AppointmentResponse(
            UUID id,
            @JsonProperty("reference_code") String referenceCode,
            @JsonProperty("service_id") UUID serviceId,
            String date,
            String start,
            String end,
            String state,
            String source,
            @JsonProperty("visitor_id") UUID visitorId,
            @JsonProperty("preferred_agent_id") UUID preferredAgentId,
            @JsonProperty("purpose_note") String purposeNote,
            String language) {}
}
