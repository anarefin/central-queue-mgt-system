package com.qms.appointment;

import com.qms.appointment.AppointmentAvailabilityRepository.SiteContext;
import com.qms.appointment.AppointmentBookingFields.Parsed;
import com.qms.appointment.AppointmentBookingFields.RescheduleParsed;
import com.qms.appointment.AppointmentBookingRepository.AppointmentRow;
import com.qms.appointment.AppointmentBookingRepository.ExpiredHold;
import com.qms.appointment.AppointmentBookingRepository.OverdueAppointment;
import com.qms.appointment.AppointmentBookingRepository.WaitlistEntry;
import com.qms.appointment.AppointmentBookingViews.AppointmentResponse;
import com.qms.appointment.AppointmentBookingViews.BookAppointmentRequest;
import com.qms.appointment.AppointmentBookingViews.CancelAppointmentRequest;
import com.qms.appointment.AppointmentBookingViews.RescheduleAppointmentRequest;
import com.qms.appointment.AppointmentBookingRepository.DueReminder;
import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import com.qms.platform.notifications.NotificationTriggerKeys;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Role;
import com.qms.platform.security.ScopeGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff appointment booking (SRS §9.2, §19.2): reception books on a visitor's behalf, including a phone-call booking
 * for a designated slot (FR-APT-013). Booking is transactional against the slot's remaining capacity, the same
 * numbers {@link AppointmentAvailabilityService#search} shows (FR-APT-011); a per-slot advisory lock, held for the
 * whole transaction, serialises two callers racing for the last seat so exactly one wins, and a per-visitor lock does
 * the same for the active-appointment cap (FR-APT-016) — the identical pattern {@code IssuanceGate} already uses for
 * a Service's daily cap.
 *
 * <p>Every field the caller supplies is already final (nothing is left for a visitor to complete later), so a booking
 * moves from {@code held_slot} to {@code booked} (§19.2) in the same transaction it is created in; only a hold a
 * future caller (ticket 41) leaves incomplete ever survives long enough for {@link AppointmentHoldExpiryScheduler} to
 * release it.
 */
@Service
@Profile(Profiles.SERVING)
public class AppointmentBookingService {

    /** Staff with {@code appointment:book}, or a registered visitor acting on their own appointment (§5.2 "S" for
     * Visitor, ticket 41) — {@code book}/{@code reschedule}/{@code cancel} each add the object-level "own" check
     * FR-CFG-105 requires for the visitor branch, since {@code PermissionMatrix} itself is staff-only. */
    static final String BOOK = "hasAuthority(T(com.qms.platform.security.Authorities).APPOINTMENT_BOOK) or hasRole('VISITOR')";

    /** Excludes characters easy to misread when read aloud or printed: 0/O, 1/I. */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int CODE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final AppointmentBookingRepository repository;
    private final AppointmentAvailabilityRepository availabilityRepository;
    private final AppointmentAvailabilityService availability;
    private final AppointmentProperties properties;
    private final LanguageProperties languages;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final CurrentUser currentUser;
    private final Clock clock;
    /** Empty outside the {@code serving} profile (the notification pipeline is @Profile(SERVING)-only, ADR-0010),
     * the same {@code com.qms.queue.TicketEvents} pattern (ticket 38, FR-NTF-003, FR-APT-050). */
    private final Optional<NotificationTrigger> notifications;

    AppointmentBookingService(
            AppointmentBookingRepository repository,
            AppointmentAvailabilityRepository availabilityRepository,
            AppointmentAvailabilityService availability,
            AppointmentProperties properties,
            LanguageProperties languages,
            AuditWriter audit,
            ScopeGuard scope,
            CurrentUser currentUser,
            Clock clock,
            Optional<NotificationTrigger> notifications) {
        this.repository = repository;
        this.availabilityRepository = availabilityRepository;
        this.availability = availability;
        this.properties = properties;
        this.languages = languages;
        this.audit = audit;
        this.scope = scope;
        this.currentUser = currentUser;
        this.clock = clock;
        this.notifications = notifications;
    }

    @PreAuthorize(BOOK)
    @Transactional
    public AppointmentResponse book(BookAppointmentRequest request) {
        AuthenticatedUser caller = currentUser.require();
        boolean visitorCaller = caller.roles().contains(Role.VISITOR);
        Parsed parsed = AppointmentBookingFields.parse(request, languages.languages(), visitorCaller ? caller.userId() : null);

        SiteContext site = availabilityRepository.siteContextOfService(parsed.serviceId()).orElseThrow(() -> AppointmentBookingFields.invalid("service_id", "not_found"));
        scope.requireSite(site.siteId());
        if (parsed.preferredAgentId() != null && !availabilityRepository.userExists(parsed.preferredAgentId())) {
            throw AppointmentBookingFields.invalid("preferred_agent_id", "not_found");
        }
        if (parsed.hasExistingVisitor() && !repository.visitorExists(parsed.visitorId())) {
            throw AppointmentBookingFields.invalid("visitor_id", "not_found");
        }
        // FR-QUE-011: the class this appointment's ticket will carry at check-in (ticket 35); must exist and be active,
        // the same rule IssuanceService applies to a class staff choose at issue.
        if (parsed.priorityClassId() != null && !repository.priorityClassUsable(parsed.priorityClassId())) {
            throw AppointmentBookingFields.invalid("priority_class_id", "not_found");
        }

        Instant now = clock.instant();
        UUID visitorId = parsed.hasExistingVisitor()
                ? parsed.visitorId()
                : repository.insertContactVisitor(parsed.contactName(), parsed.contactPhone(), parsed.contactEmail(), now);

        // FR-APT-042: an optional policy blocks further booking once a visitor has repeated no-shows in the rolling
        // window, but never a walk-in (someone standing in front of staff right now, the one source this can never
        // apply to) — off by default. The exemption is written as "every source but walk_in", not "phone or staff",
        // so the visitor self-service channel (ticket 41, source `visitor`, reusing this same method per ticket 34's
        // own notes) is covered by this same rule without changing it.
        if (properties.noShowPolicyEnabled() && !AppointmentSource.WALK_IN.equals(parsed.source())) {
            Instant windowStart = now.minus(Duration.ofDays(properties.noShowPolicyWindowDays()));
            if (repository.noShowCountForVisitor(visitorId, windowStart) >= properties.noShowPolicyThreshold()) {
                throw conflict("no_show_policy", Map.of("threshold", properties.noShowPolicyThreshold(), "window_days", properties.noShowPolicyWindowDays()));
            }
        }

        // FR-APT-016: serialised the same way IssuanceGate serialises a Service's daily cap — lock, then count, so two
        // requests for the same visitor can never both slip under the limit.
        repository.lock("appointment_visitor:" + visitorId);
        if (repository.activeCountForVisitor(visitorId) >= properties.maxActivePerVisitor()) {
            throw conflict("max_active_appointments", Map.of("limit", properties.maxActivePerVisitor()));
        }

        // FR-APT-011: the per-slot lock is held for the rest of this transaction, so a second booking for the same
        // slot blocks here until this one commits or rolls back, and then re-counts against what actually landed.
        repository.lock(slotKey(parsed.serviceId(), parsed.date(), parsed.start()));
        int capacity = availability.offeredCapacity(parsed.serviceId(), parsed.date(), parsed.start(), parsed.end())
                .orElseThrow(() -> conflict("slot_not_available", Map.of()));
        if (repository.activeCountForSlot(parsed.serviceId(), parsed.date(), parsed.start(), parsed.end()) >= capacity) {
            // FR-APT-023: a full slot on a Service with its waitlist on takes the request as a waitlist entry
            // instead of refusing it; still under the same per-slot lock, so it cannot race a seat freeing up.
            if (repository.waitlistEnabled(parsed.serviceId())) {
                return waitlist(parsed, visitorId, now);
            }
            throw conflict("slot_full", Map.of());
        }

        // `booked_by` is a staff user (FK to `users`); a visitor's own booking has none — the row's own `visitor_id`
        // already names who it is for, the same way a phone or walk-in booking's caller and its visitor differ.
        UUID actor = visitorCaller ? null : caller.userId();
        Instant holdExpiresAt = now.plus(Duration.ofMinutes(properties.holdMinutes()));
        UUID id = UUID.randomUUID();
        String referenceCode = insertHeldWithFreshReference(
                id, parsed.serviceId(), parsed.preferredAgentId(), visitorId, parsed.date(), parsed.start(), parsed.end(), parsed.source(), parsed.purposeNote(),
                parsed.language(), parsed.priorityClassId(), actor, holdExpiresAt, now);
        repository.confirm(id, now); // held_slot -> booked: every detail was already given (§19.2).

        audit.record(AuditEvent.of("appointment.booked", "appointment", id).withAfter(bookedSnapshot(referenceCode, parsed, visitorId)));
        notifyAppointment(NotificationTriggerKeys.APPOINTMENT_CONFIRMED, site.siteId(), parsed.serviceId(), visitorId, referenceCode, parsed.date(), parsed.start(), now);

        return new AppointmentResponse(
                id, referenceCode, parsed.serviceId(), parsed.date().toString(), fmt(parsed.start()), fmt(parsed.end()), "booked", parsed.source(), visitorId,
                parsed.preferredAgentId(), parsed.purposeNote(), parsed.language(), parsed.priorityClassId());
    }

    /** FR-APT-023: joins the waitlist for a full slot; no capacity consumed, no reference code minted yet. */
    private AppointmentResponse waitlist(Parsed parsed, UUID visitorId, Instant now) {
        UUID waitlistId = repository.insertWaitlistEntry(parsed.serviceId(), visitorId, parsed.date(), parsed.start(), parsed.end(), parsed.source(), parsed.purposeNote(), parsed.language(), now);
        audit.record(AuditEvent.of("appointment.waitlisted", "appointment_waitlist", waitlistId).withAfter(bookedSnapshot(null, parsed, visitorId)));
        return new AppointmentResponse(
                waitlistId, null, parsed.serviceId(), parsed.date().toString(), fmt(parsed.start()), fmt(parsed.end()), "waitlisted", parsed.source(), visitorId,
                parsed.preferredAgentId(), parsed.purposeNote(), parsed.language(), parsed.priorityClassId());
    }

    // ---- FR-APT-020, FR-APT-021: reschedule ------------------------------------------------------------------

    /**
     * Moves a booked appointment to a new slot, keeping its reference code (FR-APT-021) and recording the move in
     * the audit log — the same append-only trail every other entity's "change history" is, including the staff
     * {@code reason} when one is given. A visitor may act up to {@code qms.appointment.visitor-cutoff-minutes}
     * before the appointment's <em>current</em> slot; past that, a visitor is refused outright and only staff may
     * still act, with a reason (FR-APT-020, ticket 41). A visitor may only ever act on their own appointment (§5.2's
     * "S", FR-CFG-105's own object-level check).
     *
     * <p>The row passes through {@code rescheduled} (§19.2) for the length of this transaction: {@link
     * AppointmentBookingRepository#activeCountForSlot} already counts it there (ticket 33's own migration, in
     * anticipation of this one), so the old slot stays held while the new slot's capacity is confirmed, and a
     * concurrent booking or cancellation for either slot serialises against this one through the same per-slot
     * advisory lock {@link #book} uses.
     */
    @PreAuthorize(BOOK)
    @Transactional
    public AppointmentResponse reschedule(UUID id, RescheduleAppointmentRequest request) {
        AuthenticatedUser caller = currentUser.require();
        boolean visitorCaller = caller.roles().contains(Role.VISITOR);
        RescheduleParsed target = AppointmentBookingFields.parseReschedule(request);
        SiteContext site = siteOfAppointment(id);
        Instant now = clock.instant();

        repository.lock("appointment:" + id); // serialises against any other reschedule/cancel of this same row.
        AppointmentRow appointment = repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (visitorCaller && !appointment.visitorId().equals(caller.userId())) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!"booked".equals(appointment.state())) throw conflict("not_booked", Map.of());
        enforceCutoff(appointment.slotDate(), appointment.slotStart(), site.timezone(), target.reason(), visitorCaller, now);

        repository.lock(slotKey(appointment.serviceId(), appointment.slotDate(), appointment.slotStart()));
        if (repository.markRescheduling(id, now) == 0) throw conflict("not_booked", Map.of()); // raced past the check above.

        repository.lock(slotKey(appointment.serviceId(), target.date(), target.start()));
        int capacity = availability.offeredCapacity(appointment.serviceId(), target.date(), target.start(), target.end())
                .orElseThrow(() -> conflict("slot_not_available", Map.of()));
        if (repository.activeCountForSlot(appointment.serviceId(), target.date(), target.start(), target.end()) >= capacity) {
            throw conflict("slot_full", Map.of());
        }
        repository.applyReschedule(id, target.date(), target.start(), target.end(), now);

        audit.record(AuditEvent.of("appointment.rescheduled", "appointment", id)
                .withBefore(slotSnapshot(appointment.referenceCode(), appointment.serviceId(), appointment.slotDate(), appointment.slotStart(), appointment.slotEnd()))
                .withAfter(slotSnapshot(appointment.referenceCode(), appointment.serviceId(), target.date(), target.start(), target.end()))
                .withReason(target.reason()));
        notifyAppointment(
                NotificationTriggerKeys.APPOINTMENT_RESCHEDULED_OR_CANCELLED, site.siteId(), appointment.serviceId(), appointment.visitorId(),
                appointment.referenceCode(), target.date(), target.start(), now);

        return new AppointmentResponse(
                id, appointment.referenceCode(), appointment.serviceId(), target.date().toString(), fmt(target.start()), fmt(target.end()), "booked", appointment.source(),
                appointment.visitorId(), appointment.preferredAgentId(), appointment.purposeNote(), appointment.language(), appointment.priorityClassId());
    }

    // ---- FR-APT-020, FR-APT-022: cancellation ----------------------------------------------------------------

    /**
     * Cancels a booked appointment. Capacity is free the instant this commits ({@link
     * AppointmentBookingRepository#activeCountForSlot} excludes {@code cancelled}, FR-APT-022); if the Service's
     * waitlist is on and someone is waiting on this exact slot, the first of them (by join order) is offered it
     * (FR-APT-023), still inside the same transaction and the same per-slot lock, so nobody else can take the seat
     * first. The cutoff and reason rule is the same {@link #reschedule} enforces.
     */
    @PreAuthorize(BOOK)
    @Transactional
    public void cancel(UUID id, CancelAppointmentRequest request) {
        AuthenticatedUser caller = currentUser.require();
        boolean visitorCaller = caller.roles().contains(Role.VISITOR);
        String reason = AppointmentBookingFields.parseCancelReason(request);
        SiteContext site = siteOfAppointment(id);
        Instant now = clock.instant();

        repository.lock("appointment:" + id);
        AppointmentRow appointment = repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (visitorCaller && !appointment.visitorId().equals(caller.userId())) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!"booked".equals(appointment.state())) throw conflict("not_booked", Map.of());
        enforceCutoff(appointment.slotDate(), appointment.slotStart(), site.timezone(), reason, visitorCaller, now);

        repository.lock(slotKey(appointment.serviceId(), appointment.slotDate(), appointment.slotStart()));
        if (repository.cancel(id, now) == 0) throw conflict("not_booked", Map.of()); // raced past the check above.

        audit.record(AuditEvent.of("appointment.cancelled", "appointment", id)
                .withBefore(slotSnapshot(appointment.referenceCode(), appointment.serviceId(), appointment.slotDate(), appointment.slotStart(), appointment.slotEnd()))
                .withReason(reason));
        notifyAppointment(
                NotificationTriggerKeys.APPOINTMENT_RESCHEDULED_OR_CANCELLED, site.siteId(), appointment.serviceId(), appointment.visitorId(),
                appointment.referenceCode(), appointment.slotDate(), appointment.slotStart(), now);

        offerToWaitlistIfEnabled(appointment.serviceId(), appointment.slotDate(), appointment.slotStart(), appointment.slotEnd(), now);
    }

    private SiteContext siteOfAppointment(UUID id) {
        UUID serviceId = repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).serviceId();
        SiteContext site = availabilityRepository.siteContextOfService(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(site.siteId());
        return site;
    }

    /** FR-APT-020: a visitor may act up to the configured cut-off before the slot; past it, a visitor is refused
     * outright (ticket 41 — {@code visitor_cutoff_passed}, no reason can lift it) and only a staff caller may still
     * act, and only with a {@code reason}. */
    private void enforceCutoff(LocalDate slotDate, LocalTime slotStart, String timezone, String reason, boolean visitorCaller, Instant now) {
        Instant slotStartInstant = slotDate.atTime(slotStart).atZone(ZoneId.of(timezone)).toInstant();
        Instant cutoff = slotStartInstant.minus(Duration.ofMinutes(properties.visitorCutoffMinutes()));
        if (now.isBefore(cutoff)) return;
        if (visitorCaller) throw conflict("visitor_cutoff_passed", Map.of());
        if (reason == null || reason.isBlank()) throw conflict("cutoff_passed", Map.of("field", "reason"));
    }

    /** FR-APT-023: offers a freed slot to the first still-waiting entry, if the Service's waitlist is on and anyone is waiting. Called under the slot's advisory lock, already held by the caller. */
    private void offerToWaitlistIfEnabled(UUID serviceId, LocalDate date, LocalTime start, LocalTime end, Instant now) {
        if (!repository.waitlistEnabled(serviceId)) return;
        repository.firstWaiting(serviceId, date, start, end).ifPresent(entry -> offer(entry, now));
    }

    private void offer(WaitlistEntry entry, Instant now) {
        Instant holdExpiresAt = now.plus(Duration.ofMinutes(properties.waitlistHoldMinutes()));
        UUID appointmentId = UUID.randomUUID();
        String referenceCode = insertHeldWithFreshReference(
                appointmentId, entry.serviceId(), null, entry.visitorId(), entry.slotDate(), entry.slotStart(), entry.slotEnd(), entry.source(), entry.purposeNote(),
                entry.language(), null, null, holdExpiresAt, now);
        repository.markOffered(entry.id(), appointmentId, now);
        audit.record(AuditEvent.of("appointment.waitlist_offered", "appointment", appointmentId)
                .withAfter(slotSnapshot(referenceCode, entry.serviceId(), entry.slotDate(), entry.slotStart(), entry.slotEnd())));
        UUID siteId = availabilityRepository.siteContextOfService(entry.serviceId()).map(SiteContext::siteId).orElse(null);
        notifyAppointment(NotificationTriggerKeys.WAITLIST_SLOT_OFFERED, siteId, entry.serviceId(), entry.visitorId(), referenceCode, entry.slotDate(), entry.slotStart(), now);
    }

    /** FR-APT-012, §19.2 {@code held_slot -> [*]: hold expired}: releases every hold whose window has passed, so its capacity is free again. */
    @Transactional
    public int releaseExpiredHolds() {
        var released = repository.releaseExpiredHolds(clock.instant());
        for (ExpiredHold hold : released) {
            audit.record(AuditEvent.of("appointment.hold_expired", "appointment", hold.id()).withBefore(expiredSnapshot(hold)));
        }
        return released.size();
    }

    /**
     * FR-APT-040, FR-APT-041, §19.2 {@code booked -> no_show: grace elapsed}: the background sweep for an
     * appointment nobody ever tries to check in for, complementary to {@link AppointmentNoShowMarker} (which only
     * fires from an actual late check-in attempt, ticket 35). Every node fires the same sweep; the guarded update
     * ({@code state = 'booked'}) means whichever node's write lands first marks a given row and the rest find
     * nothing, the same ADR-0010 pattern {@link #releaseExpiredHolds} already uses. The audit entry's action and
     * shape match {@link AppointmentNoShowMarker#markAndAudit} exactly, so every no-show reads the same in the audit
     * log whichever path found it.
     */
    @Transactional
    public int markOverdueNoShows() {
        Instant now = clock.instant();
        Instant graceDeadline = now.minus(Duration.ofMinutes(properties.checkinGraceMinutes()));
        var marked = repository.sweepOverdueNoShows(graceDeadline, now);
        for (OverdueAppointment appointment : marked) {
            audit.record(AuditEvent.of("appointment.no_show", "appointment", appointment.id())
                    .withBefore(Map.of("reference_code", appointment.referenceCode(), "state", "booked")));
        }
        return marked.size();
    }

    /**
     * FR-APT-050: sends every reminder due at any of {@code qms.appointment.reminder-offsets-minutes} (default 24 h
     * and 1 h before the slot), in the visitor's preferred language (the same fallback-to-Site-default {@link
     * com.qms.notification.NotificationDispatcher} already gives every trigger, FR-NTF-022). Driven by {@link
     * AppointmentReminderScheduler}; {@link AppointmentBookingRepository#claimDueReminders} both finds and claims
     * each one, so a reminder is sent at most once however often this sweep runs (ADR-0010).
     */
    @Transactional
    public int sendDueReminders() {
        Instant now = clock.instant();
        int sent = 0;
        for (int offsetMinutes : properties.reminderOffsetsMinutes()) {
            for (DueReminder due : repository.claimDueReminders(offsetMinutes, now)) {
                notifyAppointment(
                        NotificationTriggerKeys.APPOINTMENT_REMINDER, due.siteId(), due.serviceId(), due.visitorId(), due.referenceCode(), due.slotDate(), due.slotStart(), now);
                sent++;
            }
        }
        return sent;
    }

    /** Fires one of §14.2's appointment triggers (ticket 40, FR-APT-050, FR-INT-040): the reference code stands in
     * for {@code token_number}, the same variable a queue-side ticket fills (FR-NTF-020's fixed variable set is
     * shared). {@code date}/{@code time} are the one pair {@code com.qms.notification.NotificationDispatcher} can
     * never look up itself, since it has no appointment table of its own to query. */
    private void notifyAppointment(String triggerKey, UUID siteId, UUID serviceId, UUID visitorId, String referenceCode, LocalDate date, LocalTime start, Instant now) {
        notifications.ifPresent(n -> n.fire(triggerKey, new NotificationContext(siteId, serviceId, null, visitorId, null, referenceCode, now, date.toString(), fmt(start))));
    }

    private String slotKey(UUID serviceId, LocalDate date, LocalTime start) {
        return "appointment_slot:" + serviceId + "|" + date + "|" + start;
    }

    private String insertHeldWithFreshReference(
            UUID id,
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
            UUID actor,
            Instant holdExpiresAt,
            Instant now) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = newReferenceCode();
            try {
                repository.insertHeld(id, code, serviceId, preferredAgentId, visitorId, date, start, end, source, purposeNote, language, priorityClassId, actor, holdExpiresAt, now);
                return code;
            } catch (DataIntegrityViolationException e) {
                last = e; // The reference code collided with an existing one (astronomically rare); try another.
            }
        }
        throw last;
    }

    private static String newReferenceCode() {
        StringBuilder sb = new StringBuilder("A-");
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        return sb.toString();
    }

    private static String fmt(LocalTime t) {
        return TIME.format(t);
    }

    private static Map<String, Object> bookedSnapshot(String referenceCode, Parsed parsed, UUID visitorId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reference_code", referenceCode);
        values.put("service_id", parsed.serviceId().toString());
        values.put("visitor_id", visitorId.toString());
        values.put("slot_date", parsed.date().toString());
        values.put("slot_start", fmt(parsed.start()));
        values.put("slot_end", fmt(parsed.end()));
        values.put("source", parsed.source());
        if (parsed.preferredAgentId() != null) values.put("preferred_agent_id", parsed.preferredAgentId().toString());
        if (parsed.priorityClassId() != null) values.put("priority_class_id", parsed.priorityClassId().toString());
        return values;
    }

    /** A reschedule's before/after, or a waitlist offer's after (FR-APT-021, FR-APT-023). */
    private static Map<String, Object> slotSnapshot(String referenceCode, UUID serviceId, LocalDate date, LocalTime start, LocalTime end) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reference_code", referenceCode);
        values.put("service_id", serviceId.toString());
        values.put("slot_date", date.toString());
        values.put("slot_start", fmt(start));
        values.put("slot_end", fmt(end));
        return values;
    }

    private static Map<String, Object> expiredSnapshot(ExpiredHold hold) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reference_code", hold.referenceCode());
        values.put("service_id", hold.serviceId().toString());
        values.put("slot_date", hold.slotDate().toString());
        values.put("slot_start", fmt(hold.slotStart()));
        values.put("slot_end", fmt(hold.slotEnd()));
        values.put("visitor_id", hold.visitorId().toString());
        return values;
    }

    /** The key of a §20.3 refusal (matches {@code appointment.refused.<reason>} in every language pack). */
    private static String key(String reason) {
        return "appointment.refused." + reason;
    }

    private static ApiException conflict(String reason, Map<String, Object> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.putAll(more);
        return new ApiException(ErrorCode.CONFLICT, key(reason), new Object[0], details);
    }
}
