package com.qms.configuration.notice;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.site.HierarchyService;
import com.qms.configuration.site.Site;
import com.qms.configuration.site.Zone;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notice-board content (ticket 30, FR-DSP-006, SRS §5.2 "Manage notice-board content", permission
 * {@code notice_board:manage}): images, video or rich text, scheduled per item, scoped to the Zone whose display(s)
 * show them. Management needs {@code notice_board:manage}, enforced here (API-016), and every write is scoped to a
 * site the caller holds (the Zone's own site) and audited with before/after values (FR-SEC-040). {@link #activeForZone}
 * is an unsecured read: it is embedded in the already-secured {@code GET /devices/{id}/display-state} read a display
 * itself calls (ticket 28), not exposed to staff directly.
 */
@Service
@Profile(Profiles.SERVING)
public class NoticeService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).NOTICE_BOARD_MANAGE)";

    private final NoticeRepository repository;
    private final HierarchyService hierarchy;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    NoticeService(NoticeRepository repository, HierarchyService hierarchy, AuditWriter audit, CurrentUser currentUser, ScopeGuard scope, Clock clock) {
        this.repository = repository;
        this.hierarchy = hierarchy;
        this.audit = audit;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public List<Notice> forZone(UUID zoneId) {
        scope.requireSite(hierarchy.zone(zoneId).siteId());
        return repository.forZone(zoneId);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public Notice create(NoticeRequest request) {
        Zone zone = requireZone(request.zoneId());
        Site site = hierarchy.site(zone.siteId());
        Instant now = clock.instant();
        Notice created = new Notice(
                UUID.randomUUID(),
                zone.id(),
                NoticeRules.type(request.type()),
                NoticeRules.content(request.contentI18n(), site.defaultLanguage(), site.enabledLanguages()),
                requireDates(request).startsAt(),
                requireDates(request).endsAt(),
                NoticeRules.sortOrder(request.sortOrder()),
                true,
                currentUser.require().userId(),
                now,
                now);
        repository.insert(created);
        audit.record(AuditEvent.of("notice.created", "notice", created.id()).withAfter(snapshot(created)));
        return created;
    }

    /** Replaces the editable content; the Zone a notice belongs to may also change (FR-DSP-006). */
    @PreAuthorize(MANAGE)
    @Transactional
    public Notice replace(UUID id, NoticeRequest request) {
        Notice before = require(id);
        scope.requireSite(hierarchy.zone(before.zoneId()).siteId());
        Zone zone = requireZone(request.zoneId());
        Site site = hierarchy.site(zone.siteId());
        Notice after = new Notice(
                id,
                zone.id(),
                NoticeRules.type(request.type()),
                NoticeRules.content(request.contentI18n(), site.defaultLanguage(), site.enabledLanguages()),
                requireDates(request).startsAt(),
                requireDates(request).endsAt(),
                NoticeRules.sortOrder(request.sortOrder()),
                before.active(),
                before.createdBy(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("notice.updated", "notice", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return require(id);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public Notice deactivate(UUID id) {
        Notice current = require(id);
        scope.requireSite(hierarchy.zone(current.zoneId()).siteId());
        if (!current.active()) return current;
        repository.setActive(id, false, clock.instant());
        audit.record(activeFlag("notice.deactivated", id, false));
        return require(id);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public Notice activate(UUID id) {
        Notice current = require(id);
        scope.requireSite(hierarchy.zone(current.zoneId()).siteId());
        if (current.active()) return current;
        repository.setActive(id, true, clock.instant());
        audit.record(activeFlag("notice.activated", id, true));
        return require(id);
    }

    /** The zone's scheduled playlist active right now (FR-DSP-006): unsecured, for {@code display-state} to embed (ticket 28). */
    @Transactional(readOnly = true)
    public List<Notice> activeForZone(UUID zoneId, Instant now) {
        return repository.activeForZone(zoneId, now);
    }

    private Notice require(UUID id) {
        return repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Zone requireZone(UUID zoneId) {
        if (zoneId == null) throw NoticeRules.invalid("zone_id", "NotNull");
        Zone zone = hierarchy.zone(zoneId);
        scope.requireSite(zone.siteId());
        return zone;
    }

    private record Dates(Instant startsAt, Instant endsAt) {}

    private static Dates requireDates(NoticeRequest request) {
        NoticeRules.dates(request.startsAt(), request.endsAt());
        return new Dates(request.startsAt(), request.endsAt());
    }

    private static AuditEvent activeFlag(String action, UUID id, boolean active) {
        return AuditEvent.of(action, "notice", id).withBefore(Map.of("active", !active)).withAfter(Map.of("active", active));
    }

    private static Map<String, Object> snapshot(Notice n) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("zone_id", n.zoneId().toString());
        values.put("type", n.type());
        values.put("content_i18n", n.contentI18n());
        values.put("starts_at", n.startsAt().toString());
        values.put("ends_at", n.endsAt().toString());
        values.put("sort_order", n.sortOrder());
        values.put("active", n.active());
        return values;
    }
}
