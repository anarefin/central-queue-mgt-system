package com.qms.appointment;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the appointment-availability endpoints send and receive. Times are {@code HH:mm}, dates {@code yyyy-MM-dd}, in the Site's time zone. */
public final class AppointmentAvailabilityViews {

    private AppointmentAvailabilityViews() {}

    /** One slot-template row of a request (FR-APT-002). */
    public record TemplateEntry(
            int weekday,
            String start,
            String end,
            @JsonProperty("slot_minutes") Integer slotMinutes,
            Integer capacity,
            @JsonProperty("valid_from") String validFrom,
            @JsonProperty("valid_to") String validTo) {}

    public record Template(
            UUID id,
            int weekday,
            String start,
            String end,
            @JsonProperty("slot_minutes") int slotMinutes,
            int capacity,
            @JsonProperty("valid_from") String validFrom,
            @JsonProperty("valid_to") String validTo) {}

    /** The whole slot-template set of one (level, target); a {@code PUT} replaces it wholesale. */
    public record Templates(List<Template> items) {}

    public record TemplatesRequest(List<TemplateEntry> items) {}

    /** A new exception (FR-APT-003, FR-APT-004): {@code type} is {@code blocked}, {@code extra} or {@code reduced_capacity}. */
    public record ExceptionRequest(
            String date,
            String type,
            String start,
            String end,
            @JsonProperty("slot_minutes") Integer slotMinutes,
            Integer capacity,
            @JsonProperty("note_i18n") Map<String, String> noteI18n) {}

    public record AppointmentException(
            UUID id,
            String date,
            String type,
            String start,
            String end,
            @JsonProperty("slot_minutes") Integer slotMinutes,
            Integer capacity,
            @JsonProperty("note_i18n") Map<String, String> noteI18n) {}

    public record Exceptions(List<AppointmentException> items) {}

    /**
     * A Service's booking horizon, minimum lead time (FR-APT-005) and whether its waitlist is on (FR-APT-023); any
     * left out takes the default.
     */
    public record Settings(
            @JsonProperty("booking_horizon_days") Integer bookingHorizonDays,
            @JsonProperty("min_lead_time_minutes") Integer minLeadTimeMinutes,
            @JsonProperty("waitlist_enabled") Boolean waitlistEnabled) {}

    public record Slot(String start, String end, @JsonProperty("remaining_capacity") int remainingCapacity) {}

    /** The result of a search (FR-APT-010): only slots with capacity left, for one Service on one date. */
    public record Availability(@JsonProperty("service_id") UUID serviceId, String date, List<Slot> slots) {}
}
