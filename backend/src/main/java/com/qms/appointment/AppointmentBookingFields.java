package com.qms.appointment;

import com.qms.appointment.AppointmentBookingViews.BookAppointmentRequest;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Field rules for {@code POST /appointments} (FR-APT-013, FR-APT-015). Every failure is {@code validation_failed} naming the field (SRS §20.3). */
final class AppointmentBookingFields {

    private static final int MAX_NOTE = 1000;

    private AppointmentBookingFields() {}

    /** A booking request once every field has been read and checked, ready for the service to act on. */
    record Parsed(
            UUID serviceId,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            String source,
            UUID visitorId,
            String contactName,
            String contactPhone,
            String contactEmail,
            UUID preferredAgentId,
            String purposeNote,
            String language) {

        boolean hasExistingVisitor() {
            return visitorId != null;
        }
    }

    static Parsed parse(BookAppointmentRequest request, Collection<String> installedLanguages) {
        if (request == null) throw invalid("service_id", "NotNull");
        if (request.serviceId() == null) throw invalid("service_id", "NotNull");

        LocalDate date = AppointmentAvailabilityFields.date("date", request.date());
        if (date == null) throw invalid("date", "NotNull");
        LocalTime start = AppointmentAvailabilityFields.time("start", request.start());
        if (start == null) throw invalid("start", "NotNull");
        LocalTime end = AppointmentAvailabilityFields.time("end", request.end());
        if (end == null) throw invalid("end", "NotNull");
        if (!start.isBefore(end)) throw invalid("end", "start_must_precede_end");

        String source = request.source();
        if (source == null || !AppointmentSource.ALL.contains(source)) throw invalid("source", "Pattern");

        UUID visitorId = request.visitorId();
        String contactName = blank(request.contactName());
        String contactPhone = blank(request.contactPhone());
        String contactEmail = blank(request.contactEmail());
        if (visitorId == null) {
            if (contactName == null) throw invalid("contact_name", "required");
            if (contactPhone == null) throw invalid("contact_phone", "required");
        }

        String purposeNote = blank(request.purposeNote());
        if (purposeNote != null && purposeNote.length() > MAX_NOTE) throw invalid("purpose_note", "Size");

        String language = blank(request.language());
        if (language != null && !installedLanguages.contains(language)) throw invalid("language", "unknown_language");

        return new Parsed(request.serviceId(), date, start, end, source, visitorId, contactName, contactPhone, contactEmail, request.preferredAgentId(), purposeNote, language);
    }

    private static String blank(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
