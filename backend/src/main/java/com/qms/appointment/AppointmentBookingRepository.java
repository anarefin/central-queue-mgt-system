package com.qms.appointment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for the {@code appointment} table (SRS §9.2, §19.2). {@link AppointmentAvailabilityRepository} still owns
 * templates, exceptions and settings; this is only bookings, the same split as the two tickets that introduced them.
 */
@Repository
class AppointmentBookingRepository {

    /** A booking as read back for the response (FR-APT-014, FR-APT-015). {@code priorityClassId} is the class this appointment's Ticket carries at check-in (FR-QUE-011, ticket 35); {@code null} is the default class. */
    record AppointmentRow(
            UUID id,
            String referenceCode,
            UUID serviceId,
            UUID preferredAgentId,
            UUID visitorId,
            LocalDate slotDate,
            LocalTime slotStart,
            LocalTime slotEnd,
            String state,
            String source,
            String purposeNote,
            String language,
            UUID priorityClassId) {}

    /** A held_slot row swept past its hold (FR-APT-012), enough to audit what was released. */
    record ExpiredHold(UUID id, String referenceCode, UUID serviceId, LocalDate slotDate, LocalTime slotStart, LocalTime slotEnd, UUID visitorId) {}

    /** A visitor waiting on a full slot (FR-APT-023), enough to offer it to them once it frees. */
    record WaitlistEntry(
            UUID id, UUID serviceId, UUID visitorId, LocalDate slotDate, LocalTime slotStart, LocalTime slotEnd, String source, String purposeNote, String language) {}

    private static final RowMapper<WaitlistEntry> WAITLIST_ROW = (rs, i) -> new WaitlistEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("service_id", UUID.class),
            rs.getObject("visitor_id", UUID.class),
            rs.getObject("slot_date", LocalDate.class),
            rs.getObject("slot_start", LocalTime.class),
            rs.getObject("slot_end", LocalTime.class),
            rs.getString("source"),
            rs.getString("purpose_note"),
            rs.getString("language"));

    private static final RowMapper<AppointmentRow> ROW = (rs, i) -> new AppointmentRow(
            rs.getObject("id", UUID.class),
            rs.getString("reference_code"),
            rs.getObject("service_id", UUID.class),
            rs.getObject("preferred_agent_id", UUID.class),
            rs.getObject("visitor_id", UUID.class),
            rs.getObject("slot_date", LocalDate.class),
            rs.getObject("slot_start", LocalTime.class),
            rs.getObject("slot_end", LocalTime.class),
            rs.getString("state"),
            rs.getString("source"),
            rs.getString("purpose_note"),
            rs.getString("language"),
            rs.getObject("priority_class_id", UUID.class));

    private final JdbcTemplate jdbc;

    AppointmentBookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises what is decided from a count until this transaction ends, so two requests can never both pass it (FR-APT-011, FR-APT-016). */
    void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, key);
    }

    /** How many appointments currently count against one exact slot; what search subtracts and booking checks, both against the same numbers. */
    int activeCountForSlot(UUID serviceId, LocalDate date, LocalTime start, LocalTime end) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM appointment WHERE service_id = ? AND slot_date = ? AND slot_start = ? AND slot_end = ? AND state IN ('held_slot', 'booked', 'rescheduled', 'checked_in')",
                Integer.class, serviceId, date, start, end);
        return count == null ? 0 : count;
    }

    /** FR-APT-016: how many active appointments a visitor already has, across every Service. */
    int activeCountForVisitor(UUID visitorId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM appointment WHERE visitor_id = ? AND state IN ('held_slot', 'booked', 'rescheduled', 'checked_in')",
                Integer.class, visitorId);
        return count == null ? 0 : count;
    }

    boolean visitorExists(UUID visitorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM visitor WHERE id = ?)", Boolean.class, visitorId));
    }

    /** FR-APT-015's "minimal contact record": a fresh visitor row with no external code, the same shape a walk-in pass leaves without the pass. */
    UUID insertContactVisitor(String name, String phone, String email, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, phone, email, created_at) VALUES (?, NULL, ?, NULL, ?, ?, ?)",
                id, name, phone, email, ts(now));
        return id;
    }

    /** Inserts a slot hold (FR-APT-012); the caller retries on a {@code reference_code} collision. */
    void insertHeld(
            UUID id,
            String referenceCode,
            UUID serviceId,
            UUID preferredAgentId,
            UUID visitorId,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            String source,
            String purposeNote,
            String language,
            UUID bookedBy,
            Instant holdExpiresAt,
            Instant now) {
        insertHeld(id, referenceCode, serviceId, preferredAgentId, visitorId, date, start, end, source, purposeNote, language, null, bookedBy, holdExpiresAt, now);
    }

    /** Inserts a slot hold with a chosen Priority class (FR-APT-012, FR-QUE-011, ticket 35); the caller retries on a {@code reference_code} collision. */
    void insertHeld(
            UUID id,
            String referenceCode,
            UUID serviceId,
            UUID preferredAgentId,
            UUID visitorId,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            String source,
            String purposeNote,
            String language,
            UUID priorityClassId,
            UUID bookedBy,
            Instant holdExpiresAt,
            Instant now) {
        jdbc.update(
                "INSERT INTO appointment (id, reference_code, service_id, preferred_agent_id, visitor_id, slot_date, slot_start, slot_end, state, source,"
                        + " purpose_note, language, priority_class_id, hold_expires_at, booked_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'held_slot', ?, ?, ?, ?, ?, ?, ?, ?)",
                id, referenceCode, serviceId, preferredAgentId, visitorId, date, start, end, source, purposeNote, language, priorityClassId, ts(holdExpiresAt), bookedBy, ts(now), ts(now));
    }

    /** Whether a Priority class exists and is active, so it may be given to a new appointment (FR-QUE-011, mirrors {@code IssuanceService#requireIssuableClass}). */
    boolean priorityClassUsable(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM priority_class WHERE id = ? AND active)", Boolean.class, id));
    }

    /** {@code held_slot -> booked: details confirmed} (§19.2): every field the caller gave is already in place, so this only flips the state. */
    void confirm(UUID id, Instant now) {
        jdbc.update("UPDATE appointment SET state = 'booked', hold_expires_at = NULL, updated_at = ? WHERE id = ?", ts(now), id);
    }

    /** {@code booked -> rescheduled} (§19.2): the old slot stays held (still in {@link #activeCountForSlot}'s set) while the new slot's capacity is confirmed. Guarded on {@code state = 'booked'}; 0 means it was not (a stale caller, or a race the appointment-id advisory lock should already have prevented). */
    int markRescheduling(UUID id, Instant now) {
        return jdbc.update("UPDATE appointment SET state = 'rescheduled', updated_at = ? WHERE id = ? AND state = 'booked'", ts(now), id);
    }

    /** {@code rescheduled -> booked} (§19.2, FR-APT-021): moves to the new slot, same reference code. */
    void applyReschedule(UUID id, LocalDate date, LocalTime start, LocalTime end, Instant now) {
        jdbc.update("UPDATE appointment SET slot_date = ?, slot_start = ?, slot_end = ?, state = 'booked', updated_at = ? WHERE id = ?", date, start, end, ts(now), id);
    }

    /** {@code booked -> cancelled} (§19.2, FR-APT-022): capacity is free the instant this commits, since {@link #activeCountForSlot} excludes it. Guarded the same way {@link #markRescheduling} is. */
    int cancel(UUID id, Instant now) {
        return jdbc.update("UPDATE appointment SET state = 'cancelled', updated_at = ? WHERE id = ? AND state = 'booked'", ts(now), id);
    }

    // ---- waitlist (FR-APT-023) ----------------------------------------------------------------------------------

    boolean waitlistEnabled(UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.query(
                        "SELECT waitlist_enabled FROM appointment_service_settings WHERE service_id = ?", (rs, i) -> rs.getBoolean("waitlist_enabled"), serviceId)
                .stream().findFirst().orElse(false));
    }

    UUID insertWaitlistEntry(UUID serviceId, UUID visitorId, LocalDate date, LocalTime start, LocalTime end, String source, String purposeNote, String language, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO appointment_waitlist (id, service_id, visitor_id, slot_date, slot_start, slot_end, source, purpose_note, language, state, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'waiting', ?, ?)",
                id, serviceId, visitorId, date, start, end, source, purposeNote, language, ts(now), ts(now));
        return id;
    }

    /** The first still-waiting entry for one exact slot (FR-APT-023: "the first waitlisted visitor"). */
    Optional<WaitlistEntry> firstWaiting(UUID serviceId, LocalDate date, LocalTime start, LocalTime end) {
        return jdbc.query(
                        "SELECT id, service_id, visitor_id, slot_date, slot_start, slot_end, source, purpose_note, language FROM appointment_waitlist"
                                + " WHERE service_id = ? AND slot_date = ? AND slot_start = ? AND slot_end = ? AND state = 'waiting' ORDER BY created_at LIMIT 1",
                        WAITLIST_ROW,
                        serviceId, date, start, end)
                .stream().findFirst();
    }

    void markOffered(UUID waitlistId, UUID offeredAppointmentId, Instant now) {
        jdbc.update("UPDATE appointment_waitlist SET state = 'offered', offered_appointment_id = ?, updated_at = ? WHERE id = ?", offeredAppointmentId, ts(now), waitlistId);
    }

    private static final String SELECT_ROW =
            "SELECT id, reference_code, service_id, preferred_agent_id, visitor_id, slot_date, slot_start, slot_end, state, source, purpose_note, language, priority_class_id FROM appointment";

    Optional<AppointmentRow> find(UUID id) {
        return jdbc.query(SELECT_ROW + " WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** The appointment a visitor presents by its reference code (FR-ISS-030): what a kiosk resolves from a typed code or QR, or reception from either. */
    Optional<AppointmentRow> findByReferenceCode(String referenceCode) {
        return jdbc.query(SELECT_ROW + " WHERE reference_code = ?", ROW, referenceCode).stream().findFirst();
    }

    /** {@code booked -> checked_in} (§19.2, FR-APT-030): guarded on {@code state = 'booked'}; 0 means it was not (a stale caller, or a race the appointment-id advisory lock should already have prevented). */
    int markCheckedIn(UUID id, Instant checkedInAt, Instant now) {
        return jdbc.update("UPDATE appointment SET state = 'checked_in', checked_in_at = ?, updated_at = ? WHERE id = ? AND state = 'booked'", ts(checkedInAt), ts(now), id);
    }

    /** {@code checked_in -> converted} (§19.2, FR-APT-030): the Ticket the check-in created, and the recorded difference between slot time and the actual check-in. */
    int markConverted(UUID id, UUID ticketId, int checkinVarianceSeconds, Instant now) {
        return jdbc.update(
                "UPDATE appointment SET state = 'converted', ticket_id = ?, checkin_variance_seconds = ?, updated_at = ? WHERE id = ? AND state = 'checked_in'",
                ticketId, checkinVarianceSeconds, ts(now), id);
    }

    /** {@code booked -> no_show} (§19.2, FR-APT-033, FR-APT-040): a late check-in past the grace period follows the no-show policy instead of converting. */
    int markNoShow(UUID id, Instant now) {
        return jdbc.update("UPDATE appointment SET state = 'no_show', updated_at = ? WHERE id = ? AND state = 'booked'", ts(now), id);
    }

    /** {@code held_slot -> [*]: hold expired} (§19.2): deletes every hold whose window has passed, freeing the capacity it held. */
    List<ExpiredHold> releaseExpiredHolds(Instant now) {
        return jdbc.query(
                "DELETE FROM appointment WHERE state = 'held_slot' AND hold_expires_at <= ?"
                        + " RETURNING id, reference_code, service_id, slot_date, slot_start, slot_end, visitor_id",
                (rs, i) -> new ExpiredHold(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference_code"),
                        rs.getObject("service_id", UUID.class),
                        rs.getObject("slot_date", LocalDate.class),
                        rs.getObject("slot_start", LocalTime.class),
                        rs.getObject("slot_end", LocalTime.class),
                        rs.getObject("visitor_id", UUID.class)),
                ts(now));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
