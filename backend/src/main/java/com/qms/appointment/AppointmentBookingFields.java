package com.qms.appointment;

import com.qms.appointment.AppointmentBookingViews.BookAppointmentRequest;
import com.qms.appointment.AppointmentBookingViews.CancelAppointmentRequest;
import com.qms.appointment.AppointmentBookingViews.RescheduleAppointmentRequest;
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
            String language,
            UUID priorityClassId) {

        boolean hasExistingVisitor() {
            return visitorId != null;
        }
    }

    /** A staff booking (SRS §9.2): source and visitor come from the request body, validated as ever. */
    static Parsed parse(BookAppointmentRequest request, Collection<String> installedLanguages) {
        return parse(request, installedLanguages, null);
    }

    /**
     * {@code ownVisitorId} is non-null only for a registered visitor's own self-service booking (ticket 41): the
     * source and the visitor are then never taken from the request — a visitor is never trusted to name another
     * visitor's id or to claim a staff source — and no contact fields or existing-visitor lookup applies, since the
     * caller's own JWT already names exactly one known visitor.
     */
    static Parsed parse(BookAppointmentRequest request, Collection<String> installedLanguages, UUID ownVisitorId) {
        if (request == null) throw invalid("service_id", "NotNull");
        if (request.serviceId() == null) throw invalid("service_id", "NotNull");

        LocalDate date = AppointmentAvailabilityFields.date("date", request.date());
        if (date == null) throw invalid("date", "NotNull");
        LocalTime start = AppointmentAvailabilityFields.time("start", request.start());
        if (start == null) throw invalid("start", "NotNull");
        LocalTime end = AppointmentAvailabilityFields.time("end", request.end());
        if (end == null) throw invalid("end", "NotNull");
        if (!start.isBefore(end)) throw invalid("end", "start_must_precede_end");

        String source;
        UUID visitorId;
        String contactName = null;
        String contactPhone = null;
        String contactEmail = null;
        if (ownVisitorId != null) {
            source = AppointmentSource.VISITOR;
            visitorId = ownVisitorId;
        } else {
            source = request.source();
            if (source == null || !AppointmentSource.ALL.contains(source) || AppointmentSource.VISITOR.equals(source)) throw invalid("source", "Pattern");
            visitorId = request.visitorId();
            contactName = blank(request.contactName());
            contactPhone = blank(request.contactPhone());
            contactEmail = blank(request.contactEmail());
            if (visitorId == null) {
                if (contactName == null) throw invalid("contact_name", "required");
                if (contactPhone == null) throw invalid("contact_phone", "required");
            }
        }

        String purposeNote = blank(request.purposeNote());
        if (purposeNote != null && purposeNote.length() > MAX_NOTE) throw invalid("purpose_note", "Size");

        String language = blank(request.language());
        if (language != null && !installedLanguages.contains(language)) throw invalid("language", "unknown_language");

        // A visitor's own booking never carries staff's own routing choice of Priority class (FR-QUE-011 stays a
        // staff/config decision); it may still name a preferred Agent, same as a phone booking can.
        UUID priorityClassId = ownVisitorId != null ? null : request.priorityClassId();

        return new Parsed(request.serviceId(), date, start, end, source, visitorId, contactName, contactPhone, contactEmail, request.preferredAgentId(), purposeNote, language, priorityClassId);
    }

    /** A reschedule's new slot and optional staff reason (FR-APT-020, FR-APT-021). */
    record RescheduleParsed(LocalDate date, LocalTime start, LocalTime end, String reason) {}

    static RescheduleParsed parseReschedule(RescheduleAppointmentRequest request) {
        if (request == null) throw invalid("date", "NotNull");
        LocalDate date = AppointmentAvailabilityFields.date("date", request.date());
        if (date == null) throw invalid("date", "NotNull");
        LocalTime start = AppointmentAvailabilityFields.time("start", request.start());
        if (start == null) throw invalid("start", "NotNull");
        LocalTime end = AppointmentAvailabilityFields.time("end", request.end());
        if (end == null) throw invalid("end", "NotNull");
        if (!start.isBefore(end)) throw invalid("end", "start_must_precede_end");
        return new RescheduleParsed(date, start, end, reason(request.reason()));
    }

    static String parseCancelReason(CancelAppointmentRequest request) {
        return request == null ? null : reason(request.reason());
    }

    private static String reason(String raw) {
        String trimmed = blank(raw);
        if (trimmed != null && trimmed.length() > MAX_NOTE) throw invalid("reason", "Size");
        return trimmed;
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
