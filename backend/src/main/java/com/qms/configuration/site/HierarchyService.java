package com.qms.configuration.site;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.featureflags.FeatureFlagKey;
import com.qms.platform.featureflags.FeatureFlags;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits and soft-deactivates Sites, Zones and Counters (FR-CFG-001..004). Every method needs
 * {@code config:org_sites_zones}, enforced here at the service layer (API-016) and limited to the caller's sites
 * (FR-CFG-106). Every change writes an audit entry with before and after values (FR-SEC-040).
 *
 * <p>Nothing is deleted. Deactivating a parent deactivates the active children below it, so an active zone or counter
 * always sits under an active site; reactivating a parent does not silently bring the children back.
 */
@Service
@Profile(Profiles.SERVING)
public class HierarchyService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final HierarchyRepository repository;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final LanguageProperties languages;
    private final Clock clock;
    private final FeatureFlags featureFlags;

    HierarchyService(
            HierarchyRepository repository,
            AuditWriter audit,
            CurrentUser currentUser,
            ScopeGuard scope,
            LanguageProperties languages,
            Clock clock,
            FeatureFlags featureFlags) {
        this.repository = repository;
        this.audit = audit;
        this.currentUser = currentUser;
        this.scope = scope;
        this.languages = languages;
        this.clock = clock;
        this.featureFlags = featureFlags;
    }

    // ---- sites -------------------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<Site> sites() {
        var claim = currentUser.require().siteIds();
        return repository.sites().stream().filter(site -> claim.isEmpty() || claim.contains(site.id())).toList();
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public Site site(UUID id) {
        scope.requireSite(id);
        return requireSite(id);
    }

    /**
     * A new site is outside every existing site scope, so only an organisation-wide caller may add one. Ticket 68:
     * the very first Site is never blocked (an installation always needs at least one); once one active Site
     * exists, a second is refused while {@code multi_site} is off.
     */
    @PreAuthorize(PERMISSION)
    @Transactional
    public Site createSite(String name, String code, String timezone, String address, String defaultLanguage, List<String> enabledLanguages) {
        if (!currentUser.require().siteIds().isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!featureFlags.isEnabled(FeatureFlagKey.MULTI_SITE) && repository.sites().stream().anyMatch(Site::active)) {
            throw FeatureFlags.refusal(FeatureFlagKey.MULTI_SITE);
        }
        String language = SiteRules.required("default_language", defaultLanguage, 3);
        Instant now = clock.instant();
        Site site = new Site(
                UUID.randomUUID(),
                SiteRules.required("name", name, 200),
                SiteRules.code(code),
                SiteRules.timezone(timezone),
                SiteRules.required("address", address, 500),
                language,
                SiteRules.languages(language, enabledLanguages, languages.languages()),
                true,
                false,
                now,
                now);
        try {
            repository.insert(site);
        } catch (DuplicateKeyException taken) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("field", "code"));
        }
        audit.record(AuditEvent.of("site.created", "site", site.id()).withAfter(snapshot(site)));
        return site;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Site updateSite(UUID id, UpdateSiteRequest change) {
        scope.requireSite(id);
        Site before = requireSite(id);
        String defaultLanguage = change.defaultLanguage() == null ? before.defaultLanguage() : change.defaultLanguage();
        List<String> enabled = change.enabledLanguages() == null ? before.enabledLanguages() : change.enabledLanguages();
        Site after = new Site(
                id,
                change.name() == null ? before.name() : SiteRules.required("name", change.name(), 200),
                change.code() == null ? before.code() : SiteRules.code(change.code()),
                change.timezone() == null ? before.timezone() : SiteRules.timezone(change.timezone()),
                change.address() == null ? before.address() : SiteRules.required("address", change.address(), 500),
                defaultLanguage,
                SiteRules.languages(defaultLanguage, enabled, languages.languages()),
                before.active(),
                change.clinicalSensitivity() == null ? before.clinicalSensitivity() : change.clinicalSensitivity(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        try {
            repository.update(after);
        } catch (DuplicateKeyException taken) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("field", "code"));
        }
        audit.record(AuditEvent.of("site.updated", "site", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Site deactivateSite(UUID id, String reason) {
        scope.requireSite(id);
        Site site = requireSite(id);
        if (!site.active()) return site;
        Instant now = clock.instant();
        repository.setSiteActive(id, false, now);
        audit.record(AuditEvent.of("site.deactivated", "site", id).withBefore(Map.of("active", true)).withAfter(Map.of("active", false)).withReason(reason));
        for (UUID zoneId : repository.activeZoneIdsOfSite(id)) {
            deactivateZoneRow(zoneId, now, cascadeReason("site"));
        }
        return requireSite(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Site activateSite(UUID id) {
        scope.requireSite(id);
        Site site = requireSite(id);
        if (site.active()) return site;
        repository.setSiteActive(id, true, clock.instant());
        audit.record(AuditEvent.of("site.activated", "site", id).withBefore(Map.of("active", false)).withAfter(Map.of("active", true)));
        return requireSite(id);
    }

    // ---- zones -------------------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<Zone> zones(UUID siteId) {
        scope.requireSite(siteId);
        requireSite(siteId);
        return repository.zonesOfSite(siteId);
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public Zone zone(UUID id) {
        Zone zone = requireZone(id);
        scope.requireSite(zone.siteId());
        return zone;
    }

    /** {@code request} is null (or its audio fields are) for a zone created without stating its voice-announcement
     * settings; those fields then take this build's defaults (FR-DSP-023, FR-DSP-025..027). */
    @PreAuthorize(PERMISSION)
    @Transactional
    public Zone createZone(UUID siteId, String name, String floorLabel, String buildingLabel, Integer displayOrder, CreateZoneRequest request) {
        scope.requireSite(siteId);
        requireActiveParent(requireSite(siteId).active());
        Instant now = clock.instant();
        String chime = request == null ? null : request.chime();
        Integer chimeVolume = request == null ? null : request.chimeVolume();
        String quietStartWire = request == null ? null : request.quietStart();
        String quietEndWire = request == null ? null : request.quietEnd();
        List<String> announcementLanguages = request == null ? null : request.announcementLanguages();
        Integer maxAnnounceQueueDepth = request == null ? null : request.maxAnnounceQueueDepth();
        LocalTime quietStart = SiteRules.quietTime("quiet_start", quietStartWire, null);
        LocalTime quietEnd = SiteRules.quietTime("quiet_end", quietEndWire, null);
        SiteRules.quietPeriodComplete(quietStart, quietEnd);
        String wayfindingImageUrl = request == null ? null : request.wayfindingImageUrl();
        Zone zone = new Zone(
                UUID.randomUUID(),
                siteId,
                SiteRules.required("name", name, 200),
                SiteRules.optional("building_label", buildingLabel, 100),
                SiteRules.required("floor_label", floorLabel, 100),
                SiteRules.displayOrder(displayOrder),
                true,
                SiteRules.chime(chime),
                SiteRules.chimeVolume(chimeVolume),
                quietStart,
                quietEnd,
                SiteRules.announcementLanguages(announcementLanguages, languages.languages()),
                SiteRules.maxAnnounceQueueDepth(maxAnnounceQueueDepth),
                SiteRules.optional("wayfinding_image_url", wayfindingImageUrl, SiteRules.MAX_WAYFINDING_IMAGE_URL),
                now,
                now);
        repository.insert(zone);
        audit.record(AuditEvent.of("zone.created", "zone", zone.id()).withAfter(snapshot(zone)));
        return zone;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Zone updateZone(UUID id, UpdateZoneRequest change) {
        Zone before = requireZone(id);
        scope.requireSite(before.siteId());
        LocalTime quietStart = SiteRules.quietTime("quiet_start", change.quietStart(), before.quietStart());
        LocalTime quietEnd = SiteRules.quietTime("quiet_end", change.quietEnd(), before.quietEnd());
        SiteRules.quietPeriodComplete(quietStart, quietEnd);
        Zone after = new Zone(
                id,
                before.siteId(),
                change.name() == null ? before.name() : SiteRules.required("name", change.name(), 200),
                change.buildingLabel() == null ? before.buildingLabel() : SiteRules.optional("building_label", change.buildingLabel(), 100),
                change.floorLabel() == null ? before.floorLabel() : SiteRules.required("floor_label", change.floorLabel(), 100),
                change.displayOrder() == null ? before.displayOrder() : SiteRules.displayOrder(change.displayOrder()),
                before.active(),
                change.chime() == null ? before.chime() : SiteRules.chime(change.chime()),
                change.chimeVolume() == null ? before.chimeVolume() : SiteRules.chimeVolume(change.chimeVolume()),
                quietStart,
                quietEnd,
                change.announcementLanguages() == null ? before.announcementLanguages() : SiteRules.announcementLanguages(change.announcementLanguages(), languages.languages()),
                change.maxAnnounceQueueDepth() == null ? before.maxAnnounceQueueDepth() : SiteRules.maxAnnounceQueueDepth(change.maxAnnounceQueueDepth()),
                change.wayfindingImageUrl() == null
                        ? before.wayfindingImageUrl()
                        : SiteRules.optional("wayfinding_image_url", change.wayfindingImageUrl(), SiteRules.MAX_WAYFINDING_IMAGE_URL),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("zone.updated", "zone", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Zone deactivateZone(UUID id, String reason) {
        Zone zone = requireZone(id);
        scope.requireSite(zone.siteId());
        if (!zone.active()) return zone;
        deactivateZoneRow(id, clock.instant(), reason);
        return requireZone(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Zone activateZone(UUID id) {
        Zone zone = requireZone(id);
        scope.requireSite(zone.siteId());
        if (zone.active()) return zone;
        requireActiveParent(requireSite(zone.siteId()).active());
        repository.setZoneActive(id, true, clock.instant());
        audit.record(AuditEvent.of("zone.activated", "zone", id).withBefore(Map.of("active", false)).withAfter(Map.of("active", true)));
        return requireZone(id);
    }

    // ---- counters ----------------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<Counter> counters(UUID zoneId) {
        scope.requireSite(requireZone(zoneId).siteId());
        return repository.countersOfZone(zoneId);
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public Counter counter(UUID id) {
        Counter counter = requireCounter(id);
        scope.requireSite(counter.siteId());
        return counter;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Counter createCounter(UUID zoneId, String label, String locationNote) {
        Zone zone = requireZone(zoneId);
        scope.requireSite(zone.siteId());
        requireActiveParent(zone.active());
        Instant now = clock.instant();
        Counter counter = new Counter(
                UUID.randomUUID(),
                zoneId,
                zone.siteId(),
                SiteRules.required("label", label, 30),
                SiteRules.optional("location_note", locationNote, 300),
                true,
                now,
                now);
        repository.insert(counter);
        audit.record(AuditEvent.of("counter.created", "counter", counter.id()).withAfter(snapshot(counter)));
        return counter;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Counter updateCounter(UUID id, UpdateCounterRequest change) {
        Counter before = requireCounter(id);
        scope.requireSite(before.siteId());
        Counter after = new Counter(
                id,
                before.zoneId(),
                before.siteId(),
                change.label() == null ? before.label() : SiteRules.required("label", change.label(), 30),
                change.locationNote() == null ? before.locationNote() : SiteRules.optional("location_note", change.locationNote(), 300),
                before.active(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("counter.updated", "counter", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Counter deactivateCounter(UUID id, String reason) {
        Counter counter = requireCounter(id);
        scope.requireSite(counter.siteId());
        if (!counter.active()) return counter;
        deactivateCounterRow(id, clock.instant(), reason);
        return requireCounter(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public Counter activateCounter(UUID id) {
        Counter counter = requireCounter(id);
        scope.requireSite(counter.siteId());
        if (counter.active()) return counter;
        requireActiveParent(requireZone(counter.zoneId()).active());
        repository.setCounterActive(id, true, clock.instant());
        audit.record(AuditEvent.of("counter.activated", "counter", id).withBefore(Map.of("active", false)).withAfter(Map.of("active", true)));
        return requireCounter(id);
    }

    // ---- device bootstrap reads (ticket 24) ---------------------------------------------------------------------
    // A kiosk or display authenticates with a device role, not a staff permission, so these three reads carry their
    // own narrower @PreAuthorize instead of the config:org_sites_zones check every other method here uses. They back
    // GET /config/bootstrap (SRS §20.4) and are read-only.

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional(readOnly = true)
    public Site siteForDevice(UUID id) {
        return requireSite(id);
    }

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional(readOnly = true)
    public Zone zoneForDevice(UUID id) {
        return requireZone(id);
    }

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional(readOnly = true)
    public List<Counter> countersForDevice(UUID zoneId) {
        return repository.countersOfZone(zoneId);
    }

    // ---- notice board reads ---------------------------------------------------------------------------------------
    // notice_board:manage is held by Team Admin, who does not hold config:org_sites_zones, so the notice board
    // reads the Zone and Site it needs through these narrower, scope-checked methods instead of zone() and site().

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).NOTICE_BOARD_MANAGE)")
    @Transactional(readOnly = true)
    public Zone zoneForNoticeBoard(UUID id) {
        Zone zone = requireZone(id);
        scope.requireSite(zone.siteId());
        return zone;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).NOTICE_BOARD_MANAGE)")
    @Transactional(readOnly = true)
    public Site siteForNoticeBoard(UUID id) {
        scope.requireSite(id);
        return requireSite(id);
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private void deactivateZoneRow(UUID zoneId, Instant now, String reason) {
        repository.setZoneActive(zoneId, false, now);
        audit.record(AuditEvent.of("zone.deactivated", "zone", zoneId).withBefore(Map.of("active", true)).withAfter(Map.of("active", false)).withReason(reason));
        for (UUID counterId : repository.activeCounterIdsOfZone(zoneId)) {
            deactivateCounterRow(counterId, now, cascadeReason("zone"));
        }
    }

    private void deactivateCounterRow(UUID counterId, Instant now, String reason) {
        repository.setCounterActive(counterId, false, now);
        audit.record(AuditEvent.of("counter.deactivated", "counter", counterId).withBefore(Map.of("active", true)).withAfter(Map.of("active", false)).withReason(reason));
    }

    private static String cascadeReason(String parent) {
        return "parent " + parent + " deactivated";
    }

    /** New or reactivated children need an active parent, so an active child never sits under an inactive one. */
    private static void requireActiveParent(boolean parentActive) {
        if (!parentActive) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "parent_inactive"));
    }

    private Site requireSite(UUID id) {
        return repository.site(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Zone requireZone(UUID id) {
        return repository.zone(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Counter requireCounter(UUID id) {
        return repository.counter(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static Map<String, Object> snapshot(Site site) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", site.name());
        values.put("code", site.code());
        values.put("timezone", site.timezone());
        values.put("address", site.address());
        values.put("default_language", site.defaultLanguage());
        values.put("enabled_languages", site.enabledLanguages());
        values.put("active", site.active());
        values.put("clinical_sensitivity", site.clinicalSensitivity());
        return values;
    }

    private static Map<String, Object> snapshot(Zone zone) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("site_id", zone.siteId().toString());
        values.put("name", zone.name());
        values.put("building_label", zone.buildingLabel());
        values.put("floor_label", zone.floorLabel());
        values.put("display_order", zone.displayOrder());
        values.put("active", zone.active());
        values.put("chime", zone.chime());
        values.put("chime_volume", zone.chimeVolume());
        values.put("quiet_start", zone.quietStart() == null ? null : zone.quietStart().toString());
        values.put("quiet_end", zone.quietEnd() == null ? null : zone.quietEnd().toString());
        values.put("announcement_languages", zone.announcementLanguages());
        values.put("max_announce_queue_depth", zone.maxAnnounceQueueDepth());
        values.put("wayfinding_image_url", zone.wayfindingImageUrl());
        return values;
    }

    private static Map<String, Object> snapshot(Counter counter) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("zone_id", counter.zoneId().toString());
        values.put("label", counter.label());
        values.put("location_note", counter.locationNote());
        values.put("active", counter.active());
        return values;
    }
}
