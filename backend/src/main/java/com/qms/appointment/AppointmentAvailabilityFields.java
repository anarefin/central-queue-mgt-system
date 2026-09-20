package com.qms.appointment;

import com.qms.appointment.AppointmentAvailabilityViews.ExceptionRequest;
import com.qms.appointment.AppointmentAvailabilityViews.Settings;
import com.qms.appointment.AppointmentAvailabilityViews.TemplateEntry;
import com.qms.appointment.AppointmentRows.DateException;
import com.qms.appointment.AppointmentRows.ServiceSettings;
import com.qms.appointment.AppointmentRows.Template;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Field rules for appointment availability. Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class AppointmentAvailabilityFields {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
    static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    static final Set<String> TYPES = Set.of(DateException.BLOCKED, DateException.EXTRA, DateException.REDUCED_CAPACITY);
    private static final int MAX_MINUTES = 24 * 60;
    private static final int MAX_CAPACITY = 100_000;
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_HORIZON_DAYS = 3650;
    private static final int MAX_LEAD_MINUTES = 100_000;

    private AppointmentAvailabilityFields() {}

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    static AppointmentLevel level(String wire) {
        return AppointmentLevel.tryFromWire(wire).orElseThrow(() -> invalid("level", "Pattern"));
    }

    static LocalTime time(String field, String value) {
        try {
            return value == null ? null : LocalTime.parse(value, TIME);
        } catch (DateTimeParseException e) {
            throw invalid(field, "Pattern");
        }
    }

    static LocalDate date(String field, String value) {
        try {
            return value == null ? null : LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            throw invalid(field, "Pattern");
        }
    }

    /** A slot-template set for one (level, target); every row well-formed, order not significant. */
    static List<Template> templates(AppointmentLevel level, UUID targetId, List<TemplateEntry> given) {
        if (given == null) throw invalid("items", "NotNull");
        List<Template> rows = new ArrayList<>();
        for (TemplateEntry entry : given) {
            if (entry == null) throw invalid("items", "NotNull");
            if (entry.weekday() < 1 || entry.weekday() > 7) throw invalid("weekday", "invalid_weekday");
            LocalTime start = time("start", entry.start());
            LocalTime end = time("end", entry.end());
            if (start == null) throw invalid("start", "NotNull");
            if (end == null) throw invalid("end", "NotNull");
            if (!start.isBefore(end)) throw invalid("end", "start_must_precede_end");
            Integer slotMinutes = entry.slotMinutes();
            if (slotMinutes == null || slotMinutes < 1 || slotMinutes > MAX_MINUTES) throw invalid("slot_minutes", "Range");
            Integer capacity = entry.capacity();
            if (capacity == null || capacity < 1 || capacity > MAX_CAPACITY) throw invalid("capacity", "Range");
            LocalDate validFrom = date("valid_from", entry.validFrom());
            LocalDate validTo = date("valid_to", entry.validTo());
            if (validFrom != null && validTo != null && validFrom.isAfter(validTo)) throw invalid("valid_to", "valid_from_must_precede_valid_to");
            rows.add(new Template(UUID.randomUUID(), level, targetId, entry.weekday(), start, end, slotMinutes, capacity, validFrom, validTo));
        }
        return rows;
    }

    /** A new exception; which fields are required or forbidden depends on {@code type} (FR-APT-003, FR-APT-004). */
    static DateException exception(AppointmentLevel level, UUID targetId, ExceptionRequest request, Collection<String> installed) {
        if (request == null) throw invalid("date", "NotNull");
        LocalDate date = date("date", request.date());
        if (date == null) throw invalid("date", "NotNull");
        String type = request.type();
        if (type == null || !TYPES.contains(type)) throw invalid("type", "Pattern");

        LocalTime start = null;
        LocalTime end = null;
        Integer slotMinutes = null;
        Integer capacity = null;
        if (DateException.EXTRA.equals(type)) {
            start = time("start", request.start());
            end = time("end", request.end());
            if (start == null) throw invalid("start", "NotNull");
            if (end == null) throw invalid("end", "NotNull");
            if (!start.isBefore(end)) throw invalid("end", "start_must_precede_end");
            slotMinutes = request.slotMinutes();
            if (slotMinutes == null || slotMinutes < 1 || slotMinutes > MAX_MINUTES) throw invalid("slot_minutes", "Range");
            capacity = request.capacity();
            if (capacity == null || capacity < 1 || capacity > MAX_CAPACITY) throw invalid("capacity", "Range");
        } else if (DateException.REDUCED_CAPACITY.equals(type)) {
            capacity = request.capacity();
            if (capacity == null || capacity < 0 || capacity > MAX_CAPACITY) throw invalid("capacity", "Range");
        }
        return new DateException(UUID.randomUUID(), level, targetId, date, type, start, end, slotMinutes, capacity, message("note_i18n", request.noteI18n(), installed));
    }

    static ServiceSettings settings(Settings given) {
        if (given == null) return ServiceSettings.DEFAULTS;
        int horizon = given.bookingHorizonDays() == null ? ServiceSettings.DEFAULT_HORIZON_DAYS : given.bookingHorizonDays();
        int lead = given.minLeadTimeMinutes() == null ? ServiceSettings.DEFAULT_LEAD_MINUTES : given.minLeadTimeMinutes();
        boolean waitlistEnabled = given.waitlistEnabled() == null ? ServiceSettings.DEFAULT_WAITLIST_ENABLED : given.waitlistEnabled();
        if (horizon < 1 || horizon > MAX_HORIZON_DAYS) throw invalid("booking_horizon_days", "Range");
        if (lead < 0 || lead > MAX_LEAD_MINUTES) throw invalid("min_lead_time_minutes", "Range");
        return new ServiceSettings(horizon, lead, waitlistEnabled);
    }

    /** A per-language note: only installed languages, blank texts dropped. No note at all is allowed. */
    static Map<String, String> message(String field, Map<String, String> given, Collection<String> installed) {
        Map<String, String> kept = new LinkedHashMap<>();
        if (given == null) return kept;
        if (!installed.containsAll(given.keySet())) throw invalid(field, "unknown_language");
        for (String language : installed) {
            String text = given.get(language);
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() > MAX_MESSAGE) throw invalid(field, "Size");
            if (!trimmed.isEmpty()) kept.put(language, trimmed);
        }
        return kept;
    }
}
