package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.IssuanceRulesRepository.HolidayRow;
import com.qms.issuance.IssuanceRulesRepository.HoursRow;
import com.qms.issuance.IssuanceRulesRepository.ServiceRule;
import com.qms.issuance.IssuanceRulesRepository.Settings;
import com.qms.issuance.IssuanceRulesViews.Cutoffs;
import com.qms.issuance.IssuanceRulesViews.Holiday;
import com.qms.issuance.IssuanceRulesViews.HolidayRequest;
import com.qms.issuance.IssuanceRulesViews.Holidays;
import com.qms.issuance.IssuanceRulesViews.Hours;
import com.qms.issuance.IssuanceRulesViews.WeekDay;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of the rules that decide when issuance refuses: weekly hours per Site with a per-Service override, the
 * holiday calendar, channel cut-offs (FR-CFG-020..022), a Service's daily cap, duplicate policy and roster rule
 * (FR-CFG-023, FR-ISS-003, FR-ISS-004), and maintenance mode and the rate limits (FR-OPS-043, API-090).
 *
 * <p>Hours, holidays, cut-offs and the settings need {@code config:org_sites_zones}; a Service's hours and rules need
 * {@code config:service_catalogue}. Both are enforced here (API-016) and limited to the caller's sites (FR-CFG-106); the
 * deployment-wide settings are for an organisation-wide caller only. Every change writes an audit entry with before and
 * after values (FR-SEC-040); changing nothing writes nothing.
 */
@Service
@Profile(Profiles.SERVING)
public class IssuanceRulesService {

    static final String SITES = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    static final String CATALOGUE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";
    /** The one settings row has no id of its own; its audit entries share this one. */
    static final UUID SETTINGS_ID = new UUID(0, 1);

    private final IssuanceRulesRepository repository;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final CurrentUser currentUser;
    private final LanguageProperties languages;
    private final Clock clock;

    IssuanceRulesService(
            IssuanceRulesRepository repository, AuditWriter audit, ScopeGuard scope, CurrentUser currentUser, LanguageProperties languages, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.scope = scope;
        this.currentUser = currentUser;
        this.languages = languages;
        this.clock = clock;
    }

    // ---- hours (FR-CFG-020) -----------------------------------------------------------------------------------

    @PreAuthorize(SITES)
    @Transactional(readOnly = true)
    public Hours siteHours(UUID siteId) {
        requireSite(siteId);
        return view(repository.hours(IssuanceRulesRepository.SITE, siteId));
    }

    @PreAuthorize(SITES)
    @Transactional
    public Hours setSiteHours(UUID siteId, Hours request) {
        requireSite(siteId);
        return setHours(IssuanceRulesRepository.SITE, siteId, request);
    }

    @PreAuthorize(CATALOGUE)
    @Transactional(readOnly = true)
    public Hours serviceHours(UUID serviceId) {
        requireService(serviceId);
        return view(repository.hours(IssuanceRulesRepository.SERVICE, serviceId));
    }

    /** Replaces the Service's own week; an empty week removes the override, so the Service follows its Site again. */
    @PreAuthorize(CATALOGUE)
    @Transactional
    public Hours setServiceHours(UUID serviceId, Hours request) {
        requireService(serviceId);
        return setHours(IssuanceRulesRepository.SERVICE, serviceId, request);
    }

    private Hours setHours(String scopeType, UUID scopeId, Hours request) {
        List<HoursRow> after = IssuanceRuleFields.week(request == null ? null : request.days());
        List<HoursRow> before = repository.hours(scopeType, scopeId);
        if (!before.equals(after)) {
            repository.replaceHours(scopeType, scopeId, after);
            audit.record(AuditEvent.of("business_hours.updated", "business_hours", scopeId)
                    .withBefore(Map.of("scope_type", scopeType, "days", view(before).days()))
                    .withAfter(Map.of("scope_type", scopeType, "days", view(after).days())));
        }
        return view(after);
    }

    // ---- holidays (FR-CFG-021) --------------------------------------------------------------------------------

    @PreAuthorize(SITES)
    @Transactional(readOnly = true)
    public Holidays holidays(UUID siteId) {
        requireSite(siteId);
        return new Holidays(repository.holidays(siteId).stream().map(IssuanceRulesService::view).toList());
    }

    @PreAuthorize(SITES)
    @Transactional
    public Holiday addHoliday(UUID siteId, HolidayRequest request) {
        requireSite(siteId);
        HolidayRow holiday = IssuanceRuleFields.holiday(siteId, request);
        if (repository.holidayOn(siteId, holiday.date()).isPresent()) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "holiday_exists"));
        repository.insertHoliday(holiday);
        audit.record(AuditEvent.of("holiday.created", "holiday", holiday.id()).withAfter(snapshot(holiday)));
        return view(holiday);
    }

    @PreAuthorize(SITES)
    @Transactional
    public void removeHoliday(UUID siteId, UUID id) {
        requireSite(siteId);
        HolidayRow holiday = repository.holiday(id).filter(h -> h.siteId().equals(siteId)).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        repository.deleteHoliday(id);
        audit.record(AuditEvent.of("holiday.deleted", "holiday", id).withBefore(snapshot(holiday)));
    }

    // ---- cut-offs (FR-CFG-022) --------------------------------------------------------------------------------

    @PreAuthorize(SITES)
    @Transactional(readOnly = true)
    public Cutoffs cutoffs(UUID siteId) {
        requireSite(siteId);
        return new Cutoffs(repository.cutoffs(siteId));
    }

    @PreAuthorize(SITES)
    @Transactional
    public Cutoffs setCutoffs(UUID siteId, Cutoffs request) {
        requireSite(siteId);
        Map<String, Integer> after = IssuanceRuleFields.cutoffs(request == null ? null : request.minutesBeforeClose());
        Map<String, Integer> before = repository.cutoffs(siteId);
        if (!before.equals(after)) {
            repository.replaceCutoffs(siteId, after);
            audit.record(AuditEvent.of("channel_cutoff.updated", "channel_cutoff", siteId)
                    .withBefore(Map.of("minutes_before_close", before))
                    .withAfter(Map.of("minutes_before_close", after)));
        }
        return new Cutoffs(after);
    }

    // ---- a Service's cap, duplicate policy and roster rule ----------------------------------------------------

    @PreAuthorize(CATALOGUE)
    @Transactional(readOnly = true)
    public IssuanceRulesViews.ServiceRule serviceRule(UUID serviceId) {
        requireService(serviceId);
        return view(repository.serviceRule(serviceId));
    }

    @PreAuthorize(CATALOGUE)
    @Transactional
    public IssuanceRulesViews.ServiceRule setServiceRule(UUID serviceId, IssuanceRulesViews.ServiceRule request) {
        requireService(serviceId);
        ServiceRule after = IssuanceRuleFields.serviceRule(request, languages.languages());
        ServiceRule before = repository.serviceRule(serviceId);
        if (!before.equals(after)) {
            repository.saveServiceRule(serviceId, after, clock.instant());
            audit.record(AuditEvent.of("service_issuance_rule.updated", "service_issuance_rule", serviceId).withBefore(snapshot(before)).withAfter(snapshot(after)));
        }
        return view(after);
    }

    // ---- remote-join policy (ticket 42, FR-MOB-010..011) -------------------------------------------------------

    @PreAuthorize(CATALOGUE)
    @Transactional(readOnly = true)
    public IssuanceRulesViews.RemoteRule remoteRule(UUID serviceId) {
        requireService(serviceId);
        return view(repository.remoteRule(serviceId));
    }

    @PreAuthorize(CATALOGUE)
    @Transactional
    public IssuanceRulesViews.RemoteRule setRemoteRule(UUID serviceId, IssuanceRulesViews.RemoteRule request) {
        requireService(serviceId);
        IssuanceRulesRepository.RemoteRule after = IssuanceRuleFields.remoteRule(request);
        IssuanceRulesRepository.RemoteRule before = repository.remoteRule(serviceId);
        if (!before.equals(after)) {
            repository.saveRemoteRule(serviceId, after, clock.instant());
            audit.record(AuditEvent.of("service_remote_rule.updated", "service_remote_rule", serviceId).withBefore(snapshot(before)).withAfter(snapshot(after)));
        }
        return view(after);
    }

    // ---- a Site's own coordinates (ticket 42, FR-MOB-011) -------------------------------------------------------

    @PreAuthorize(SITES)
    @Transactional(readOnly = true)
    public IssuanceRulesViews.SiteLocation siteLocation(UUID siteId) {
        requireSite(siteId);
        return repository.siteLocation(siteId).map(IssuanceRulesService::view).orElse(new IssuanceRulesViews.SiteLocation(null, null));
    }

    @PreAuthorize(SITES)
    @Transactional
    public IssuanceRulesViews.SiteLocation setSiteLocation(UUID siteId, IssuanceRulesViews.SiteLocation request) {
        requireSite(siteId);
        IssuanceRulesRepository.SiteLocation after = IssuanceRuleFields.siteLocation(request);
        IssuanceRulesRepository.SiteLocation before = repository.siteLocation(siteId).orElse(null);
        if (!after.equals(before)) {
            repository.saveSiteLocation(siteId, after, clock.instant());
            audit.record(AuditEvent.of("site_location.updated", "site_location", siteId).withBefore(snapshot(before)).withAfter(snapshot(after)));
        }
        return view(after);
    }

    // ---- maintenance mode and rate limits (FR-OPS-043, API-090) -----------------------------------------------

    @PreAuthorize(SITES)
    @Transactional(readOnly = true)
    public IssuanceRulesViews.Settings settings() {
        requireOrganisationWide();
        return view(repository.settings());
    }

    @PreAuthorize(SITES)
    @Transactional
    public IssuanceRulesViews.Settings setSettings(IssuanceRulesViews.Settings request) {
        requireOrganisationWide();
        Settings after = IssuanceRuleFields.settings(request, languages.languages());
        Settings before = repository.settings();
        if (!before.equals(after)) {
            repository.saveSettings(after, clock.instant());
            String action = before.maintenanceEnabled() == after.maintenanceEnabled()
                    ? "issuance_settings.updated"
                    : after.maintenanceEnabled() ? "issuance.maintenance_enabled" : "issuance.maintenance_disabled";
            audit.record(AuditEvent.of(action, "issuance_settings", SETTINGS_ID).withBefore(snapshot(before)).withAfter(snapshot(after)));
        }
        return view(after);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void requireSite(UUID siteId) {
        if (!repository.siteExists(siteId)) throw new ApiException(ErrorCode.NOT_FOUND);
        scope.requireSite(siteId);
    }

    private void requireService(UUID serviceId) {
        scope.requireSite(repository.siteOfService(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    /** These settings are the deployment's, outside every Site's scope. */
    private void requireOrganisationWide() {
        if (!currentUser.require().siteIds().isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private static Hours view(List<HoursRow> rows) {
        return new Hours(rows.stream()
                .map(r -> new WeekDay(r.weekday(), r.open().format(IssuanceRuleFields.TIME), r.close().format(IssuanceRuleFields.TIME)))
                .toList());
    }

    private static Holiday view(HolidayRow h) {
        return new Holiday(h.id(), h.date().toString(), h.name(), h.halfDay(), h.closeTime() == null ? null : h.closeTime().format(IssuanceRuleFields.TIME));
    }

    private static IssuanceRulesViews.ServiceRule view(ServiceRule rule) {
        return new IssuanceRulesViews.ServiceRule(rule.dailyCap(), rule.capMessage(), rule.duplicatePolicy(), rule.requireAgent());
    }

    private static IssuanceRulesViews.Settings view(Settings s) {
        return new IssuanceRulesViews.Settings(s.maintenanceEnabled(), s.maintenanceMessage(), s.deviceLimitPerMinute(), s.visitorLimitPerHour());
    }

    private static IssuanceRulesViews.RemoteRule view(IssuanceRulesRepository.RemoteRule r) {
        return new IssuanceRulesViews.RemoteRule(r.virtualQueueEnabled(), r.maxDistanceMeters(), r.maxRemoteSharePct(), r.joinWindowMinutes(), r.arrivalDeadlineMinutes());
    }

    private static IssuanceRulesViews.SiteLocation view(IssuanceRulesRepository.SiteLocation l) {
        return new IssuanceRulesViews.SiteLocation(l.latitude(), l.longitude());
    }

    private static Map<String, Object> snapshot(HolidayRow h) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("site_id", h.siteId().toString());
        values.put("date", h.date().toString());
        values.put("name", h.name());
        values.put("half_day", h.halfDay());
        values.put("close_time", h.closeTime() == null ? null : h.closeTime().format(IssuanceRuleFields.TIME));
        return values;
    }

    private static Map<String, Object> snapshot(ServiceRule r) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("daily_cap", r.dailyCap());
        values.put("cap_message_i18n", r.capMessage());
        values.put("duplicate_policy", r.duplicatePolicy());
        values.put("require_agent", r.requireAgent());
        return values;
    }

    private static Map<String, Object> snapshot(Settings s) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("maintenance_enabled", s.maintenanceEnabled());
        values.put("maintenance_message_i18n", s.maintenanceMessage());
        values.put("device_limit_per_minute", s.deviceLimitPerMinute());
        values.put("visitor_limit_per_hour", s.visitorLimitPerHour());
        return values;
    }

    private static Map<String, Object> snapshot(IssuanceRulesRepository.RemoteRule r) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("virtual_queue_enabled", r.virtualQueueEnabled());
        values.put("max_distance_m", r.maxDistanceMeters());
        values.put("max_remote_share_pct", r.maxRemoteSharePct());
        values.put("join_window_minutes", r.joinWindowMinutes());
        values.put("arrival_deadline_minutes", r.arrivalDeadlineMinutes());
        return values;
    }

    private static Map<String, Object> snapshot(IssuanceRulesRepository.SiteLocation l) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("latitude", l == null ? null : l.latitude());
        values.put("longitude", l == null ? null : l.longitude());
        return values;
    }
}
