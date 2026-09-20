package com.qms.appointment;

import com.qms.appointment.AppointmentAvailabilityRepository.SiteContext;
import com.qms.appointment.AppointmentAvailabilityViews.Availability;
import com.qms.appointment.AppointmentAvailabilityViews.ExceptionRequest;
import com.qms.appointment.AppointmentAvailabilityViews.Exceptions;
import com.qms.appointment.AppointmentAvailabilityViews.Settings;
import com.qms.appointment.AppointmentAvailabilityViews.Templates;
import com.qms.appointment.AppointmentAvailabilityViews.TemplatesRequest;
import com.qms.appointment.AppointmentRows.DateException;
import com.qms.appointment.AppointmentRows.ServiceSettings;
import com.qms.appointment.AppointmentRows.Template;
import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of appointment availability at Service, Team or Agent level, most specific winning (FR-APT-001), its
 * exceptions and admin overrides (FR-APT-003, FR-APT-004), a Service's booking horizon and minimum lead time
 * (FR-APT-005), and the search a visitor or any staff channel runs against it (FR-APT-010).
 *
 * <p>Administration needs {@code config:service_catalogue}, scoped to the caller's Sites (FR-CFG-106), enforced here
 * (API-016). The search is read-only and open to any authenticated caller: visitor self-service does not exist yet
 * (ticket 41), so for now this is how reception, kiosk and a future visitor session alike look up what is open.
 */
@Service
@Profile(Profiles.SERVING)
public class AppointmentAvailabilityService {

    static final String CATALOGUE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";
    static final String SEARCH = "isAuthenticated()";

    private final AppointmentAvailabilityRepository repository;
    private final AppointmentBookingRepository bookings;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final CurrentUser currentUser;
    private final LanguageProperties languages;
    private final Clock clock;

    AppointmentAvailabilityService(
            AppointmentAvailabilityRepository repository,
            AppointmentBookingRepository bookings,
            AuditWriter audit,
            ScopeGuard scope,
            CurrentUser currentUser,
            LanguageProperties languages,
            Clock clock) {
        this.repository = repository;
        this.bookings = bookings;
        this.audit = audit;
        this.scope = scope;
        this.currentUser = currentUser;
        this.languages = languages;
        this.clock = clock;
    }

    // ---- slot templates (FR-APT-002) ---------------------------------------------------------------------------

    @PreAuthorize(CATALOGUE)
    @Transactional(readOnly = true)
    public Templates templates(String levelWire, UUID targetId) {
        AppointmentLevel level = AppointmentAvailabilityFields.level(levelWire);
        requireTarget(level, targetId);
        return view(repository.templates(level, targetId));
    }

    /** Replaces the whole set for this (level, target); an empty list clears it. */
    @PreAuthorize(CATALOGUE)
    @Transactional
    public Templates setTemplates(String levelWire, UUID targetId, TemplatesRequest request) {
        AppointmentLevel level = AppointmentAvailabilityFields.level(levelWire);
        requireTarget(level, targetId);
        List<Template> after = AppointmentAvailabilityFields.templates(level, targetId, request == null ? null : request.items());
        List<Template> before = repository.templates(level, targetId);
        if (!sameContent(before, after)) {
            repository.replaceTemplates(level, targetId, after);
            audit.record(AuditEvent.of("appointment_slot_template.updated", "appointment_slot_template", targetId)
                    .withBefore(Map.of("level", level.wire(), "items", snapshotTemplates(before)))
                    .withAfter(Map.of("level", level.wire(), "items", snapshotTemplates(after))));
        }
        return view(after);
    }

    // ---- exceptions (FR-APT-003, FR-APT-004) -------------------------------------------------------------------

    @PreAuthorize(CATALOGUE)
    @Transactional(readOnly = true)
    public Exceptions exceptions(String levelWire, UUID targetId) {
        AppointmentLevel level = AppointmentAvailabilityFields.level(levelWire);
        requireTarget(level, targetId);
        return viewExceptions(repository.exceptions(level, targetId));
    }

    @PreAuthorize(CATALOGUE)
    @Transactional
    public AppointmentAvailabilityViews.AppointmentException addException(String levelWire, UUID targetId, ExceptionRequest request) {
        AppointmentLevel level = AppointmentAvailabilityFields.level(levelWire);
        requireTarget(level, targetId);
        DateException row = AppointmentAvailabilityFields.exception(level, targetId, request, languages.languages());
        if (repository.exceptionOn(level, targetId, row.date()).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "exception_exists"));
        }
        repository.insertException(row);
        audit.record(AuditEvent.of("appointment_exception.created", "appointment_exception", row.id()).withAfter(snapshot(row)));
        return view(row);
    }

    @PreAuthorize(CATALOGUE)
    @Transactional
    public void removeException(String levelWire, UUID targetId, UUID id) {
        AppointmentLevel level = AppointmentAvailabilityFields.level(levelWire);
        requireTarget(level, targetId);
        DateException row = repository.exception(id)
                .filter(e -> e.level() == level && e.targetId().equals(targetId))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        repository.deleteException(id);
        audit.record(AuditEvent.of("appointment_exception.deleted", "appointment_exception", id).withBefore(snapshot(row)));
    }

    // ---- booking horizon and minimum lead time (FR-APT-005) ----------------------------------------------------

    @PreAuthorize(CATALOGUE)
    @Transactional(readOnly = true)
    public Settings settings(UUID serviceId) {
        requireService(serviceId);
        ServiceSettings s = repository.settings(serviceId).orElse(ServiceSettings.DEFAULTS);
        return new Settings(s.bookingHorizonDays(), s.minLeadTimeMinutes());
    }

    @PreAuthorize(CATALOGUE)
    @Transactional
    public Settings setSettings(UUID serviceId, Settings request) {
        requireService(serviceId);
        ServiceSettings after = AppointmentAvailabilityFields.settings(request);
        ServiceSettings before = repository.settings(serviceId).orElse(ServiceSettings.DEFAULTS);
        if (!before.equals(after)) {
            repository.saveSettings(serviceId, after, clock.instant());
            audit.record(AuditEvent.of("appointment_settings.updated", "appointment_settings", serviceId)
                    .withBefore(Map.of("booking_horizon_days", before.bookingHorizonDays(), "min_lead_time_minutes", before.minLeadTimeMinutes()))
                    .withAfter(Map.of("booking_horizon_days", after.bookingHorizonDays(), "min_lead_time_minutes", after.minLeadTimeMinutes())));
        }
        return new Settings(after.bookingHorizonDays(), after.minLeadTimeMinutes());
    }

    // ---- search (FR-APT-010) ------------------------------------------------------------------------------------

    @PreAuthorize(SEARCH)
    @Transactional(readOnly = true)
    public Availability search(UUID serviceId, String dateParam) {
        LocalDate date = AppointmentAvailabilityFields.date("date", dateParam);
        if (date == null) throw AppointmentAvailabilityFields.invalid("date", "NotNull");
        Optional<Day> day = day(serviceId, date);
        if (day.isEmpty()) return new Availability(serviceId, date.toString(), List.of());

        List<AppointmentAvailabilityViews.Slot> out = new ArrayList<>();
        for (SlotGenerator.Slot slot : day.get().slots()) {
            if (!offered(day.get(), date, slot)) continue;
            // "Remaining capacity" (FR-APT-010) subtracts every appointment §19.2 counts as still consuming this slot,
            // the same numbers ticket 33's booking checks and holds itself to (FR-APT-011).
            int active = bookings.activeCountForSlot(serviceId, date, slot.start(), slot.end());
            int remaining = slot.capacity() - active;
            if (remaining <= 0) continue;
            out.add(new AppointmentAvailabilityViews.Slot(fmt(slot.start()), fmt(slot.end()), remaining));
        }
        return new Availability(serviceId, date.toString(), out);
    }

    /**
     * The raw, defined capacity of one exact slot (service, date, start, end) if it is currently on offer at all —
     * every rule {@link #search} applies (horizon, lead time, business hours/holiday, exceptions) but, unlike search,
     * not reduced by existing bookings: the booking service subtracts those itself, atomically, inside its own
     * per-slot lock (FR-APT-011), so the two must never race against different snapshots of the same count.
     */
    Optional<Integer> offeredCapacity(UUID serviceId, LocalDate date, LocalTime start, LocalTime end) {
        Optional<Day> day = day(serviceId, date);
        if (day.isEmpty()) return Optional.empty();
        for (SlotGenerator.Slot slot : day.get().slots()) {
            if (slot.start().equals(start) && slot.end().equals(end)) {
                return offered(day.get(), date, slot) ? Optional.of(slot.capacity()) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** One Service's resolved slots for one date, with what {@code offered} needs to also apply the lead time (FR-APT-005). */
    private record Day(ZoneId zone, ZonedDateTime earliestStart, List<SlotGenerator.Slot> slots) {}

    private Optional<Day> day(UUID serviceId, LocalDate date) {
        SiteContext site = repository.siteContextOfService(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if ("walk_in_only".equals(site.bookingMode())) return Optional.empty();

        ZoneId zone = ZoneId.of(site.timezone());
        ZonedDateTime now = clock.instant().atZone(zone);
        LocalDate today = now.toLocalDate();
        ServiceSettings settings = repository.settings(serviceId).orElse(ServiceSettings.DEFAULTS);
        if (date.isBefore(today) || date.isAfter(today.plusDays(settings.bookingHorizonDays()))) {
            return Optional.empty();
        }

        Optional<UUID> teamId = repository.teamIdOfService(serviceId);
        int weekday = date.getDayOfWeek().getValue();
        List<Template> serviceTemplates = byWeekday(repository.templates(AppointmentLevel.SERVICE, serviceId), weekday);
        List<Template> teamTemplates = teamId.map(t -> byWeekday(repository.templates(AppointmentLevel.TEAM, t), weekday)).orElse(List.of());
        List<Template> effectiveTemplates = AppointmentAvailabilityResolver.mostSpecific(List.of(), teamTemplates, serviceTemplates);

        Optional<DateException> serviceException = repository.exceptionOn(AppointmentLevel.SERVICE, serviceId, date);
        Optional<DateException> teamException = teamId.flatMap(t -> repository.exceptionOn(AppointmentLevel.TEAM, t, date));
        DateException effectiveException =
                AppointmentAvailabilityResolver.mostSpecific(Optional.<DateException>empty(), teamException, serviceException).orElse(null);

        Optional<BusinessWindow.Window> window = BusinessWindow.forDate(
                date.getDayOfWeek(), repository.week(site.siteId(), serviceId), repository.holidayOn(site.siteId(), date).orElse(null));

        List<SlotGenerator.Slot> slots = SlotGenerator.generate(effectiveTemplates, date, window, effectiveException);
        ZonedDateTime earliestStart = now.plusMinutes(settings.minLeadTimeMinutes());
        return Optional.of(new Day(zone, earliestStart, slots));
    }

    private static boolean offered(Day day, LocalDate date, SlotGenerator.Slot slot) {
        if (slot.capacity() <= 0) return false;
        ZonedDateTime start = date.atTime(slot.start()).atZone(day.zone());
        return !start.isBefore(day.earliestStart());
    }

    private static List<Template> byWeekday(List<Template> rows, int weekday) {
        return rows.stream().filter(r -> r.weekday() == weekday).toList();
    }

    // ---- scope -------------------------------------------------------------------------------------------------

    private void requireTarget(AppointmentLevel level, UUID targetId) {
        switch (level) {
            case SERVICE -> requireService(targetId);
            case TEAM -> requireTeam(targetId);
            case AGENT -> requireAgent(targetId);
        }
    }

    private void requireService(UUID serviceId) {
        UUID site = repository.siteContextOfService(serviceId).map(SiteContext::siteId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(site);
    }

    private void requireTeam(UUID teamId) {
        UUID site = repository.siteOfTeam(teamId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(site);
    }

    /** An Agent has no single Site; scoped in by any Site any of their Teams puts them in (empty claim means org-wide). */
    private void requireAgent(UUID userId) {
        if (!repository.userExists(userId)) throw new ApiException(ErrorCode.NOT_FOUND);
        Set<UUID> claim = currentUser.require().siteIds();
        if (claim.isEmpty()) return;
        Set<UUID> candidates = repository.sitesOfAgent(userId);
        if (candidates.stream().noneMatch(claim::contains)) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    // ---- view mapping --------------------------------------------------------------------------------------------

    private static String fmt(LocalTime t) {
        return AppointmentAvailabilityFields.TIME.format(t);
    }

    private static Templates view(List<Template> rows) {
        return new Templates(rows.stream()
                .map(r -> new AppointmentAvailabilityViews.Template(
                        r.id(), r.weekday(), fmt(r.start()), fmt(r.end()), r.slotMinutes(), r.capacity(),
                        r.validFrom() == null ? null : r.validFrom().toString(), r.validTo() == null ? null : r.validTo().toString()))
                .toList());
    }

    private static AppointmentAvailabilityViews.AppointmentException view(DateException r) {
        return new AppointmentAvailabilityViews.AppointmentException(
                r.id(), r.date().toString(), r.type(),
                r.start() == null ? null : fmt(r.start()), r.end() == null ? null : fmt(r.end()),
                r.slotMinutes(), r.capacity(), r.noteI18n());
    }

    private static Exceptions viewExceptions(List<DateException> rows) {
        return new Exceptions(rows.stream().map(AppointmentAvailabilityService::view).toList());
    }

    private static boolean sameContent(List<Template> a, List<Template> b) {
        return contentKeys(a).equals(contentKeys(b));
    }

    private static List<String> contentKeys(List<Template> rows) {
        return rows.stream()
                .map(r -> String.join(
                        "|",
                        String.valueOf(r.weekday()), r.start().toString(), r.end().toString(), String.valueOf(r.slotMinutes()), String.valueOf(r.capacity()),
                        r.validFrom() == null ? "" : r.validFrom().toString(), r.validTo() == null ? "" : r.validTo().toString()))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static List<Map<String, Object>> snapshotTemplates(List<Template> rows) {
        return rows.stream().map(AppointmentAvailabilityService::snapshot).toList();
    }

    private static Map<String, Object> snapshot(Template r) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("weekday", r.weekday());
        values.put("start", fmt(r.start()));
        values.put("end", fmt(r.end()));
        values.put("slot_minutes", r.slotMinutes());
        values.put("capacity", r.capacity());
        values.put("valid_from", r.validFrom() == null ? null : r.validFrom().toString());
        values.put("valid_to", r.validTo() == null ? null : r.validTo().toString());
        return values;
    }

    private static Map<String, Object> snapshot(DateException r) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("date", r.date().toString());
        values.put("type", r.type());
        values.put("start", r.start() == null ? null : fmt(r.start()));
        values.put("end", r.end() == null ? null : fmt(r.end()));
        values.put("slot_minutes", r.slotMinutes());
        values.put("capacity", r.capacity());
        values.put("note_i18n", r.noteI18n());
        return values;
    }
}
