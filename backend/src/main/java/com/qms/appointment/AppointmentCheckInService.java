package com.qms.appointment;

import com.qms.appointment.AppointmentAvailabilityRepository.SiteContext;
import com.qms.appointment.AppointmentBookingRepository.AppointmentRow;
import com.qms.appointment.AppointmentCheckInViews.CheckInRequest;
import com.qms.appointment.AppointmentCheckInViews.CheckInResponse;
import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssuanceService.AppointmentCheckinCommand;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.TicketResponse;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.featureflags.FeatureFlagKey;
import com.qms.platform.featureflags.FeatureFlags;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appointment check-in (SRS §8.4, §9.4; FR-ISS-030..033, FR-APT-030..032, §19.2). A visitor with a {@code booked}
 * appointment presents its reference code — typed or scanned as a QR at the kiosk, or given at reception (FR-ISS-030)
 * — within a configurable window around the slot (default 30 minutes before to 15 minutes after, FR-ISS-031).
 *
 * <p>On time, the appointment moves {@code checked_in -> converted} in the same transaction that creates its Ticket
 * (FR-APT-030, §19.2): the Ticket carries the appointment's Priority class (FR-QUE-011) and is ordered from the later
 * of the slot time and this check-in (FR-QUE-020), plus a configured bonus (FR-QUE-020, FR-APT-032), so keeping the
 * appointment is rewarded without ever displacing a Ticket already being served — the queue only ever orders {@code
 * waiting} Tickets, and this only ever adds one (FR-APT-031). This conversion is not run through the general
 * issuance gates (business hours, the Service's daily cap, a duplicate-ticket policy): the appointment's own booking
 * already reserved this capacity, so check-in fulfils a reservation rather than making a fresh request.
 *
 * <p>Arriving more than the window's lead time early leaves the appointment {@code booked} and offers a walk-in
 * Ticket instead, through the same gates any other walk-in goes through (FR-ISS-032). Arriving past the grace period
 * follows the no-show policy: the appointment is marked {@code no_show} on the spot, freeing its capacity (§9.5,
 * FR-APT-040, FR-APT-041), and check-in is refused.
 */
@Service
@Profile(Profiles.SERVING)
public class AppointmentCheckInService {

    static final String CHECKIN = "hasAuthority(T(com.qms.platform.security.Authorities).APPOINTMENT_CHECKIN)";

    private final AppointmentBookingRepository repository;
    private final AppointmentAvailabilityRepository availabilityRepository;
    private final AppointmentProperties properties;
    private final IssuanceService issuance;
    private final AppointmentNoShowMarker noShowMarker;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final FeatureFlags featureFlags;

    AppointmentCheckInService(
            AppointmentBookingRepository repository,
            AppointmentAvailabilityRepository availabilityRepository,
            AppointmentProperties properties,
            IssuanceService issuance,
            AppointmentNoShowMarker noShowMarker,
            AuditWriter audit,
            ScopeGuard scope,
            CurrentUser currentUser,
            Clock clock,
            FeatureFlags featureFlags) {
        this.repository = repository;
        this.availabilityRepository = availabilityRepository;
        this.properties = properties;
        this.issuance = issuance;
        this.noShowMarker = noShowMarker;
        this.audit = audit;
        this.scope = scope;
        this.currentUser = currentUser;
        this.clock = clock;
        this.featureFlags = featureFlags;
    }

    /** Reception check-in (FR-ISS-030): the caller is a signed-in staff member. */
    @PreAuthorize(CHECKIN)
    @Transactional
    public CheckInResponse checkInAsStaff(CheckInRequest request) {
        UUID actor = currentUser.require().userId();
        return checkIn(request, actor, ActorType.STAFF, Channels.RECEPTION);
    }

    /** Kiosk check-in by typed code or camera QR (FR-ISS-030): the caller is a paired kiosk device. */
    @Transactional
    public CheckInResponse checkInAsKiosk(CheckInRequest request) {
        UUID device = currentUser.require().userId();
        return checkIn(request, device, ActorType.DEVICE, Channels.KIOSK);
    }

    private CheckInResponse checkIn(CheckInRequest request, UUID actorId, ActorType actorType, String walkInChannel) {
        featureFlags.require(FeatureFlagKey.APPOINTMENT);
        String referenceCode = referenceCode(request);
        UUID id = repository.findByReferenceCode(referenceCode).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).id();

        repository.lock("appointment:" + id); // serialises against a concurrent reschedule, cancel or check-in of this same row.
        AppointmentRow appointment = repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        SiteContext site = availabilityRepository.siteContextOfService(appointment.serviceId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(site.siteId());
        if (!"booked".equals(appointment.state())) throw conflict("not_booked", Map.of());

        ZoneId zone = ZoneId.of(site.timezone());
        Instant slotStart = appointment.slotDate().atTime(appointment.slotStart()).atZone(zone).toInstant();
        Instant windowStart = slotStart.minus(Duration.ofMinutes(properties.checkinWindowBeforeMinutes()));
        Instant windowEnd = slotStart.plus(Duration.ofMinutes(properties.checkinGraceMinutes()));
        Instant now = clock.instant();

        if (now.isBefore(windowStart)) return earlyWalkIn(appointment, actorId, actorType, walkInChannel);
        if (now.isAfter(windowEnd)) throw lateNoShow(appointment, now);
        return convert(appointment, slotStart, now, actorId, actorType);
    }

    /** FR-ISS-032: too early for the window; the appointment stays {@code booked} and a walk-in Ticket is offered instead. */
    private CheckInResponse earlyWalkIn(AppointmentRow appointment, UUID actorId, ActorType actorType, String walkInChannel) {
        var command = new IssueCommand(appointment.serviceId(), walkInChannel, actorId, actorType, null, null, appointment.visitorId(), false, null, null, null);
        TicketResponse ticket = issuance.issue(command);
        audit.record(AuditEvent.of("appointment.checkin_early_walk_in", "appointment", appointment.id())
                .withAfter(Map.of("ticket_id", ticket.id().toString())));
        return new CheckInResponse(appointment.id(), appointment.referenceCode(), "walk_in", appointment.state(), ticket);
    }

    /**
     * FR-ISS-033, §9.5, FR-APT-040, FR-APT-041: past the grace period, follows the no-show policy instead of
     * converting. The mark commits in its own transaction ({@link AppointmentNoShowMarker}) before this refusal is
     * thrown, so the state change survives the rollback that throwing it causes in the caller's own transaction.
     */
    private ApiException lateNoShow(AppointmentRow appointment, Instant now) {
        noShowMarker.markAndAudit(appointment, now);
        return conflict("no_show", Map.of());
    }

    /**
     * FR-APT-030..032, §19.2: on time. {@code booked -> checked_in -> converted} within this one transaction, the
     * same two-step pattern {@link AppointmentBookingService#reschedule} uses for its own intermediate state.
     */
    private CheckInResponse convert(AppointmentRow appointment, Instant slotStart, Instant now, UUID actorId, ActorType actorType) {
        if (repository.markCheckedIn(appointment.id(), now, now) == 0) throw conflict("not_booked", Map.of());

        // FR-QUE-020: effective wait is measured from the later of the slot time and this check-in.
        Instant queuedAt = now.isAfter(slotStart) ? now : slotStart;
        var command = new AppointmentCheckinCommand(appointment.serviceId(), appointment.visitorId(), appointment.priorityClassId(), queuedAt, now, actorId, actorType);
        TicketResponse ticket = issuance.issueForAppointmentCheckin(command);

        // FR-APT-030: the difference between slot time and the actual check-in, in seconds; positive is late, negative is early.
        int varianceSeconds = (int) Duration.between(slotStart, now).getSeconds();
        repository.markConverted(appointment.id(), ticket.id(), varianceSeconds, now);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ticket_id", ticket.id().toString());
        after.put("checkin_variance_seconds", varianceSeconds);
        if (appointment.priorityClassId() != null) after.put("priority_class_id", appointment.priorityClassId().toString());
        audit.record(AuditEvent.of("appointment.checked_in", "appointment", appointment.id()).withAfter(after));

        return new CheckInResponse(appointment.id(), appointment.referenceCode(), "converted", "converted", ticket);
    }

    private static String referenceCode(CheckInRequest request) {
        String code = request == null ? null : request.referenceCode();
        if (code == null || code.isBlank()) throw invalid("reference_code", "required");
        return code.strip();
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    private static ApiException conflict(String reason, Map<String, Object> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.putAll(more);
        return new ApiException(ErrorCode.CONFLICT, "appointment.refused." + reason, new Object[0], details);
    }
}
