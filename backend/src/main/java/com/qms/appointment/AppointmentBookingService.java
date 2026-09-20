package com.qms.appointment;

import com.qms.appointment.AppointmentAvailabilityRepository.SiteContext;
import com.qms.appointment.AppointmentBookingFields.Parsed;
import com.qms.appointment.AppointmentBookingRepository.ExpiredHold;
import com.qms.appointment.AppointmentBookingViews.AppointmentResponse;
import com.qms.appointment.AppointmentBookingViews.BookAppointmentRequest;
import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
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

    static final String BOOK = "hasAuthority(T(com.qms.platform.security.Authorities).APPOINTMENT_BOOK)";

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

    AppointmentBookingService(
            AppointmentBookingRepository repository,
            AppointmentAvailabilityRepository availabilityRepository,
            AppointmentAvailabilityService availability,
            AppointmentProperties properties,
            LanguageProperties languages,
            AuditWriter audit,
            ScopeGuard scope,
            CurrentUser currentUser,
            Clock clock) {
        this.repository = repository;
        this.availabilityRepository = availabilityRepository;
        this.availability = availability;
        this.properties = properties;
        this.languages = languages;
        this.audit = audit;
        this.scope = scope;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize(BOOK)
    @Transactional
    public AppointmentResponse book(BookAppointmentRequest request) {
        Parsed parsed = AppointmentBookingFields.parse(request, languages.languages());

        SiteContext site = availabilityRepository.siteContextOfService(parsed.serviceId()).orElseThrow(() -> AppointmentBookingFields.invalid("service_id", "not_found"));
        scope.requireSite(site.siteId());
        if (parsed.preferredAgentId() != null && !availabilityRepository.userExists(parsed.preferredAgentId())) {
            throw AppointmentBookingFields.invalid("preferred_agent_id", "not_found");
        }
        if (parsed.hasExistingVisitor() && !repository.visitorExists(parsed.visitorId())) {
            throw AppointmentBookingFields.invalid("visitor_id", "not_found");
        }

        Instant now = clock.instant();
        UUID visitorId = parsed.hasExistingVisitor()
                ? parsed.visitorId()
                : repository.insertContactVisitor(parsed.contactName(), parsed.contactPhone(), parsed.contactEmail(), now);

        // FR-APT-016: serialised the same way IssuanceGate serialises a Service's daily cap — lock, then count, so two
        // requests for the same visitor can never both slip under the limit.
        repository.lock("appointment_visitor:" + visitorId);
        if (repository.activeCountForVisitor(visitorId) >= properties.maxActivePerVisitor()) {
            throw conflict("max_active_appointments", Map.of("limit", properties.maxActivePerVisitor()));
        }

        // FR-APT-011: the per-slot lock is held for the rest of this transaction, so a second booking for the same
        // slot blocks here until this one commits or rolls back, and then re-counts against what actually landed.
        repository.lock(slotKey(parsed));
        int capacity = availability.offeredCapacity(parsed.serviceId(), parsed.date(), parsed.start(), parsed.end())
                .orElseThrow(() -> conflict("slot_not_available", Map.of()));
        if (repository.activeCountForSlot(parsed.serviceId(), parsed.date(), parsed.start(), parsed.end()) >= capacity) {
            throw conflict("slot_full", Map.of());
        }

        UUID actor = currentUser.require().userId();
        Instant holdExpiresAt = now.plus(Duration.ofMinutes(properties.holdMinutes()));
        UUID id = UUID.randomUUID();
        String referenceCode = insertWithFreshReference(id, parsed, visitorId, actor, holdExpiresAt, now);
        repository.confirm(id, now); // held_slot -> booked: every detail was already given (§19.2).

        audit.record(AuditEvent.of("appointment.booked", "appointment", id).withAfter(bookedSnapshot(referenceCode, parsed, visitorId)));

        return new AppointmentResponse(
                id, referenceCode, parsed.serviceId(), parsed.date().toString(), fmt(parsed.start()), fmt(parsed.end()), "booked", parsed.source(), visitorId,
                parsed.preferredAgentId(), parsed.purposeNote(), parsed.language());
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

    private String slotKey(Parsed parsed) {
        return "appointment_slot:" + parsed.serviceId() + "|" + parsed.date() + "|" + parsed.start();
    }

    private String insertWithFreshReference(UUID id, Parsed parsed, UUID visitorId, UUID actor, Instant holdExpiresAt, Instant now) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = newReferenceCode();
            try {
                repository.insertHeld(
                        id, code, parsed.serviceId(), parsed.preferredAgentId(), visitorId, parsed.date(), parsed.start(), parsed.end(), parsed.source(),
                        parsed.purposeNote(), parsed.language(), actor, holdExpiresAt, now);
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
