package com.qms.appointment;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/** Rows as held and computed on, independent of the wire shape in {@link AppointmentAvailabilityViews} (SRS §9.1). */
final class AppointmentRows {

    private AppointmentRows() {}

    /** A slot template row (FR-APT-002): a weekday, its window, slot length, concurrent capacity and validity range. */
    record Template(
            UUID id, AppointmentLevel level, UUID targetId, int weekday, LocalTime start, LocalTime end, int slotMinutes, int capacity, LocalDate validFrom, LocalDate validTo) {

        boolean coversDate(LocalDate date) {
            return (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || !date.isAfter(validTo));
        }
    }

    /** A one-off exception for a date (FR-APT-003, FR-APT-004). */
    record DateException(
            UUID id, AppointmentLevel level, UUID targetId, LocalDate date, String type, LocalTime start, LocalTime end, Integer slotMinutes, Integer capacity, Map<String, String> noteI18n) {

        static final String BLOCKED = "blocked";
        static final String EXTRA = "extra";
        static final String REDUCED_CAPACITY = "reduced_capacity";
    }

    /** A Service's booking horizon, minimum lead time (FR-APT-005) and whether its waitlist is on (FR-APT-023). */
    record ServiceSettings(int bookingHorizonDays, int minLeadTimeMinutes, boolean waitlistEnabled) {
        static final int DEFAULT_HORIZON_DAYS = 30;
        static final int DEFAULT_LEAD_MINUTES = 120;
        static final boolean DEFAULT_WAITLIST_ENABLED = false;
        static final ServiceSettings DEFAULTS = new ServiceSettings(DEFAULT_HORIZON_DAYS, DEFAULT_LEAD_MINUTES, DEFAULT_WAITLIST_ENABLED);
    }
}
