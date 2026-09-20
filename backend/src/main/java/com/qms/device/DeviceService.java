package com.qms.device;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.branding.BrandingService;
import com.qms.configuration.catalogue.CatalogueService;
import com.qms.configuration.catalogue.ServiceEntry;
import com.qms.configuration.catalogue.ServiceGroup;
import com.qms.configuration.notice.Notice;
import com.qms.configuration.notice.NoticeService;
import com.qms.configuration.site.Counter;
import com.qms.configuration.site.HierarchyService;
import com.qms.configuration.site.Site;
import com.qms.configuration.site.Zone;
import com.qms.device.BootstrapResponse.Branding;
import com.qms.device.BootstrapResponse.CounterLayout;
import com.qms.device.BootstrapResponse.Layout;
import com.qms.device.BootstrapResponse.ServiceTreeEntry;
import com.qms.device.BootstrapResponse.ServiceTreeGroup;
import com.qms.device.BootstrapResponse.ZoneLayout;
import com.qms.identity.AccessToken;
import com.qms.identity.AccessTokenService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Role;
import com.qms.platform.security.ScopeGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairing, refresh, heartbeat and bootstrap for a device (SRS §20.2, §20.4, FR-OPS-011/041), and the fleet
 * administration around it: issuing pairing codes, listing devices, revoking a credential and pushing a reload or
 * configuration update (FR-OPS-042). Devices sit under a Site (kiosk) or Zone (display) exactly like a Counter, so
 * fleet administration reuses {@code config:org_sites_zones}, the permission that already covers that physical
 * hierarchy (FR-CFG-001..004), rather than adding a permission the SRS §5.2 matrix does not have.
 */
@Service
@Profile(Profiles.SERVING)
public class DeviceService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** A device that has heartbeat within this long is shown online; beyond it but within STALE_AFTER it is stale. */
    private static final Duration ONLINE_WITHIN = Duration.ofSeconds(150);
    private static final Duration STALE_WITHIN = Duration.ofMinutes(10);

    private final DeviceRepository devices;
    private final DevicePairingCodeRepository pairingCodes;
    private final DeviceRefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final HierarchyService hierarchy;
    private final CatalogueService catalogue;
    private final BrandingService branding;
    private final DisplayStateReads displayReads;
    private final NoticeService notices;
    private final DeviceProperties properties;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final RealtimePublisher realtime;
    private final Clock clock;

    DeviceService(
            DeviceRepository devices,
            DevicePairingCodeRepository pairingCodes,
            DeviceRefreshTokenRepository refreshTokens,
            AccessTokenService accessTokens,
            HierarchyService hierarchy,
            CatalogueService catalogue,
            BrandingService branding,
            DisplayStateReads displayReads,
            NoticeService notices,
            DeviceProperties properties,
            AuditWriter audit,
            CurrentUser currentUser,
            ScopeGuard scope,
            RealtimePublisher realtime,
            Clock clock) {
        this.devices = devices;
        this.pairingCodes = pairingCodes;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.hierarchy = hierarchy;
        this.catalogue = catalogue;
        this.branding = branding;
        this.displayReads = displayReads;
        this.notices = notices;
        this.properties = properties;
        this.audit = audit;
        this.currentUser = currentUser;
        this.scope = scope;
        this.realtime = realtime;
        this.clock = clock;
    }

    // ---- pairing (device-facing, public) ------------------------------------------------------------------------

    @Transactional
    public DeviceSession pair(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) throw new ApiException(ErrorCode.TOKEN_INVALID);
        Instant now = clock.instant();
        DevicePairingCodeRepository.Row code =
                pairingCodes.lockByHash(hash(rawCode)).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        if (code.usedAt() != null || !code.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        pairingCodes.markUsed(code.id(), now);

        Device device = new Device(
                UUID.randomUUID(),
                Role.fromWire(code.kind()),
                code.siteId(),
                code.zoneId(),
                code.label(),
                true,
                now,
                null,
                null,
                DeviceRules.DEFAULT_LAYOUT,
                Map.of(),
                List.of("en"), // pairing is public and unauthenticated, so the site's own default language cannot be read here (ticket 28); an
                // administrator sets a real language cycle with PUT /devices/{id}/display-config once paired.
                DeviceRules.DEFAULT_LANGUAGE_CYCLE_SECONDS,
                DeviceRules.DEFAULT_NEXT_N,
                DeviceRules.DEFAULT_HIGHLIGHT_SECONDS,
                DeviceRules.DEFAULT_COLUMNS,
                "zone",
                List.of(),
                now,
                now);
        devices.insert(device);
        audit.record(AuditEvent.of("device.paired", "device", device.id()).withAfter(snapshot(device)));
        return issueSession(device, UUID.randomUUID(), now);
    }

    // ---- silent refresh (device-facing, public) -----------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    public DeviceSession refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new ApiException(ErrorCode.TOKEN_INVALID);
        Instant now = clock.instant();
        DeviceRefreshTokenRepository.Row row =
                refreshTokens.lockByHash(hash(rawToken)).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));

        if (row.usedAt() != null) {
            refreshTokens.revokeFamily(row.familyId(), now);
            audit.record(AuditEvent.of("device.refresh.reuse_detected", "device", row.deviceId())
                    .withAfter(Map.of("family_id", row.familyId().toString())));
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        if (row.revokedAt() != null || !row.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        Device device = devices.findById(row.deviceId()).orElse(null);
        if (device == null || !device.active()) {
            refreshTokens.revokeFamily(row.familyId(), now);
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }

        refreshTokens.markUsed(row.id(), now);
        return issueSession(device, row.familyId(), now);
    }

    // ---- heartbeat (device-facing) ------------------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional
    public void heartbeat(UUID id, String appVersion) {
        requireSelf(id);
        Device device = requireDevice(id);
        if (!device.active()) throw new ApiException(ErrorCode.TOKEN_INVALID);
        devices.heartbeat(id, clock.instant(), DeviceRules.required("app_version", appVersion, 100));
    }

    // ---- bootstrap (device-facing) -------------------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional(readOnly = true)
    public BootstrapResponse bootstrap() {
        UUID deviceId = currentUser.require().userId();
        Device device = requireDevice(deviceId);
        if (!device.active()) throw new ApiException(ErrorCode.TOKEN_INVALID);

        Site site = hierarchy.siteForDevice(device.siteId());
        Layout layout = null;
        if (device.zoneId() != null) {
            Zone zone = hierarchy.zoneForDevice(device.zoneId());
            List<Counter> counters = hierarchy.countersForDevice(zone.id());
            layout = new Layout(new ZoneLayout(
                    zone.id(),
                    zone.name(),
                    zone.buildingLabel(),
                    zone.floorLabel(),
                    counters.stream().filter(Counter::active).map(c -> new CounterLayout(c.id(), c.label())).toList()));
        }

        // A kiosk only ever walks the visitor to a Service it can actually issue through (ticket 25, SRS §8.2); a
        // display shows the whole tree, since it never issues anything itself.
        boolean kiosk = device.kind() == Role.KIOSK;
        List<ServiceGroup> groups = catalogue.serviceTreeForDevice(device.siteId());
        List<ServiceTreeGroup> tree = groups.stream()
                .map(group -> {
                    List<ServiceEntry> services = catalogue.servicesForDevice(group.id());
                    List<ServiceTreeEntry> entries = services.stream()
                            .filter(s -> !kiosk || s.channels().contains("kiosk"))
                            .map(s -> new ServiceTreeEntry(s.id(), s.nameI18n(), s.visitorIdentifier()))
                            .toList();
                    BootstrapResponse.CustomLevel customLevel = group.customLevelOptions().isEmpty()
                            ? null
                            : new BootstrapResponse.CustomLevel(
                                    group.customLevelNameI18n(),
                                    group.customLevelOptions().stream()
                                            .map(o -> new BootstrapResponse.CustomLevelOption(o.id(), o.nameI18n()))
                                            .toList());
                    return new ServiceTreeGroup(group.id(), group.nameI18n(), entries, group.teamSelectable(), group.individualSelectable(), customLevel);
                })
                .filter(g -> !kiosk || !g.services().isEmpty())
                .toList();

        var orgBranding = branding.forDevice();
        var template = branding.templateForDevice();
        Branding responseBranding =
                new Branding(site.name(), site.defaultLanguage(), orgBranding.orgName(), orgBranding.primaryColor(), orgBranding.logoUrl());
        var printTemplate = new BootstrapResponse.PrintTemplate(template.fields(), template.noticeLine());
        return new BootstrapResponse(responseBranding, site.enabledLanguages(), layout, tree, printTemplate);
    }

    // ---- fleet administration (staff-facing) --------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional
    public PairingCodeResponse createPairingCode(String kindWire, UUID siteId, UUID zoneId, String label) {
        scope.requireSite(siteId);
        Role kind = DeviceRules.kind(kindWire);
        DeviceRules.zoneMatchesKind(kind, zoneId);
        String trimmedLabel = DeviceRules.required("label", label, 100);
        hierarchy.site(siteId); // 404 if the site does not exist
        if (zoneId != null && !hierarchy.zone(zoneId).siteId().equals(siteId)) {
            throw DeviceRules.invalid("zone_id", "zone_not_in_site");
        }

        String raw = DeviceRules.randomPairingCode();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.pairingCodeTtl());
        UUID id = UUID.randomUUID();
        pairingCodes.insert(id, hash(raw), kind.wire(), siteId, zoneId, trimmedLabel, now, expiresAt);
        audit.record(AuditEvent.of("device.pairing_code.created", "device_pairing_code", id)
                .withAfter(Map.of("kind", kind.wire(), "site_id", siteId.toString(), "label", trimmedLabel)));
        return new PairingCodeResponse(raw, expiresAt);
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<DeviceView> devices() {
        Set<UUID> claim = currentUser.require().siteIds();
        return devices.all().stream().filter(d -> claim.isEmpty() || claim.contains(d.siteId())).map(this::view).toList();
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public DeviceView device(UUID id) {
        Device device = requireDevice(id);
        scope.requireSite(device.siteId());
        return view(device);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public DeviceView revoke(UUID id) {
        Device device = requireDevice(id);
        scope.requireSite(device.siteId());
        if (device.active()) {
            Instant now = clock.instant();
            devices.setActive(id, false, now);
            refreshTokens.revokeAllForDevice(id, now);
            audit.record(AuditEvent.of("device.revoked", "device", id)
                    .withBefore(Map.of("active", true)).withAfter(Map.of("active", false)));
            realtime.principalChanged(id.toString()); // drops the device's live socket at once (FR-DSP-013)
        }
        return view(requireDevice(id));
    }

    /** Pushes a reload or configuration update to a live device over its {@code device:{id}} topic (FR-OPS-042). */
    @PreAuthorize(PERMISSION)
    @Transactional
    public void command(UUID id, String command) {
        Device device = requireDevice(id);
        scope.requireSite(device.siteId());
        String eventType = DeviceRules.commandEventType(command);
        if (!device.active()) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "device_inactive"));
        realtime.publish(Topics.device(id), eventType, clock.instant(), Map.of("command", command));
        audit.record(AuditEvent.of("device.command_pushed", "device", id).withAfter(Map.of("command", command)));
    }

    /**
     * Sets a display's layout, language cycle, column set, next-N depth, highlight period and its assignment within
     * its zone (ticket 28, FR-DSP-001, FR-DSP-002). A null field in the request keeps this build's default. Pushes
     * {@code config.changed} on the device's own topic, the same push {@code command(reload/config_changed)} already
     * uses, so a live display refetches at once instead of waiting for its next heartbeat-driven reload.
     */
    @PreAuthorize(PERMISSION)
    @Transactional
    public DisplayConfigResponse updateDisplayConfig(UUID id, DisplayConfigRequest request) {
        Device before = requireDevice(id);
        scope.requireSite(before.siteId());
        if (before.kind() != Role.DISPLAY) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "not_a_display"));
        Site site = hierarchy.site(before.siteId());

        String layout = DeviceRules.layout(request.layout());
        Map<String, Object> layoutConfig = DeviceRules.layoutConfig(layout, request.layoutConfig());
        if (DeviceRules.LAYOUT_SINGLE_COUNTER.equals(layout)) {
            UUID counterId = UUID.fromString((String) layoutConfig.get("counter_id"));
            if (!devices.counterInZone(counterId, before.zoneId())) throw DeviceRules.invalid("layout_config.counter_id", "counter_not_in_zone");
        }
        List<String> languageCycle = DeviceRules.languageCycle(request.languageCycle(), site.defaultLanguage(), site.enabledLanguages());
        int languageCycleSeconds = DeviceRules.languageCycleSeconds(request.languageCycleSeconds());
        int nextN = DeviceRules.positiveInt("next_n", request.nextN(), DeviceRules.DEFAULT_NEXT_N, DeviceRules.MAX_NEXT_N);
        int highlightSeconds = DeviceRules.positiveInt(
                "highlight_seconds", request.highlightSeconds(), DeviceRules.DEFAULT_HIGHLIGHT_SECONDS, DeviceRules.MAX_HIGHLIGHT_SECONDS);
        List<String> columns = DeviceRules.columns(request.columns());
        DisplayConfigRequest.AssignmentRequest assignmentRequest = request.assignment();
        String assignmentScope = DeviceRules.assignmentScope(assignmentRequest == null ? null : assignmentRequest.scope());
        List<UUID> assignmentIds = DeviceRules.assignmentIds(assignmentScope, assignmentRequest == null ? null : assignmentRequest.ids());
        if ("counters".equals(assignmentScope)) {
            for (UUID counterId : assignmentIds) {
                if (!devices.counterInZone(counterId, before.zoneId())) throw DeviceRules.invalid("assignment.ids", "counter_not_in_zone");
            }
        } else if ("queues".equals(assignmentScope)) {
            for (UUID serviceId : assignmentIds) {
                if (!devices.serviceServedInZone(serviceId, before.zoneId())) throw DeviceRules.invalid("assignment.ids", "service_not_in_zone");
            }
        }

        Device after = new Device(
                before.id(), before.kind(), before.siteId(), before.zoneId(), before.label(), before.active(),
                before.pairedAt(), before.lastHeartbeatAt(), before.lastAppVersion(),
                layout, layoutConfig, languageCycle, languageCycleSeconds, nextN, highlightSeconds, columns, assignmentScope, assignmentIds,
                before.createdAt(), clock.instant());
        if (configSnapshot(after).equals(configSnapshot(before))) return DisplayConfigResponse.from(before);
        devices.updateDisplayConfig(after);
        audit.record(AuditEvent.of("device.display_config_updated", "device", id)
                .withBefore(configSnapshot(before)).withAfter(configSnapshot(after)));
        realtime.publish(Topics.device(id), "config.changed", clock.instant(), Map.of());
        return DisplayConfigResponse.from(after);
    }

    /** Body of {@code GET /devices/{id}/display-state} (FR-DSP-012): the display's own resume read, no staff permission needed. */
    @PreAuthorize("hasRole('DISPLAY')")
    @Transactional(readOnly = true)
    public DisplayStateResponse displayState(UUID id) {
        requireSelf(id);
        Device device = requireDevice(id);
        if (!device.active()) throw new ApiException(ErrorCode.TOKEN_INVALID);
        if (device.kind() != Role.DISPLAY || device.zoneId() == null) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "not_a_display"));
        Zone zone = hierarchy.zoneForDevice(device.zoneId());
        return new DisplayStateResponse(
                new DisplayStateResponse.ZoneRef(
                        zone.id(),
                        zone.name(),
                        zone.buildingLabel(),
                        zone.floorLabel(),
                        zone.chime(),
                        zone.chimeVolume(),
                        zone.quietStart() == null ? null : zone.quietStart().toString(),
                        zone.quietEnd() == null ? null : zone.quietEnd().toString(),
                        zone.announcementLanguages(),
                        zone.maxAnnounceQueueDepth()),
                device.layout(),
                device.layoutConfig(),
                device.languageCycle(),
                device.languageCycleSeconds(),
                device.columns(),
                device.nextN(),
                device.highlightSeconds(),
                new DisplayStateResponse.Assignment(device.assignmentScope(), device.assignmentIds()),
                displayReads.serving(zone.id()).stream().map(DeviceService::servingEntry).toList(),
                displayReads.next(zone.id()).stream().map(DeviceService::nextGroupEntry).toList(),
                notices.activeForZone(zone.id(), clock.instant()).stream().map(DeviceService::noticeEntry).toList(),
                displayReads.summary(zone.id()).stream().map(DeviceService::summaryEntry).toList());
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private DeviceSession issueSession(Device device, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.insert(UUID.randomUUID(), familyId, device.id(), hash(raw), now, now.plus(properties.refreshTokenTtl()));

        Set<UUID> siteIds = Set.of(device.siteId());
        // A display's zone rides in the "groups" claim: API-011's closed claim set has no "zones" claim, and reusing
        // "groups" (a generic scoped-id list) avoids widening that schema for one device kind (SRS gap, ticket 24).
        Set<UUID> groupIds = device.zoneId() == null ? Set.of() : Set.of(device.zoneId());
        AccessToken accessToken = accessTokens.issue(device.id(), Set.of(device.kind()), siteIds, groupIds);
        return new DeviceSession(device, accessToken, raw);
    }

    private void requireSelf(UUID id) {
        if (!currentUser.require().userId().equals(id)) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private Device requireDevice(UUID id) {
        return devices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private DeviceView view(Device device) {
        return new DeviceView(
                device.id(),
                device.kind().wire(),
                device.siteId(),
                device.zoneId(),
                device.label(),
                device.active(),
                device.pairedAt(),
                device.lastHeartbeatAt(),
                device.lastAppVersion(),
                connectivity(device, clock.instant()),
                device.layout(),
                device.layoutConfig(),
                device.languageCycle(),
                device.languageCycleSeconds(),
                device.nextN(),
                device.highlightSeconds(),
                device.columns(),
                device.assignmentScope(),
                device.assignmentIds());
    }

    private static String connectivity(Device device, Instant now) {
        if (device.lastHeartbeatAt() == null) return "offline";
        Duration since = Duration.between(device.lastHeartbeatAt(), now);
        if (since.compareTo(ONLINE_WITHIN) <= 0) return "online";
        if (since.compareTo(STALE_WITHIN) <= 0) return "stale";
        return "offline";
    }

    private static Map<String, Object> snapshot(Device device) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", device.kind().wire());
        values.put("site_id", device.siteId().toString());
        values.put("zone_id", device.zoneId() == null ? null : device.zoneId().toString());
        values.put("label", device.label());
        values.put("active", device.active());
        return values;
    }

    private static Map<String, Object> configSnapshot(Device device) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("layout", device.layout());
        values.put("layout_config", device.layoutConfig());
        values.put("language_cycle", device.languageCycle());
        values.put("language_cycle_seconds", device.languageCycleSeconds());
        values.put("next_n", device.nextN());
        values.put("highlight_seconds", device.highlightSeconds());
        values.put("columns", device.columns());
        values.put("assignment_scope", device.assignmentScope());
        values.put("assignment_ids", device.assignmentIds().stream().map(UUID::toString).toList());
        return values;
    }

    private static DisplayStateResponse.ServingEntry servingEntry(DisplayStateReads.ServingRow row) {
        return new DisplayStateResponse.ServingEntry(
                row.counterId(), row.counterLabel(), row.tokenNumber(), row.state(), row.serviceId(), row.serviceNames(), row.staffName(),
                row.tokenPrefix(), row.tokenPrefixSpoken(), row.announceVisitorName());
    }

    private static DisplayStateResponse.NextGroupEntry nextGroupEntry(DisplayStateReads.NextGroup group) {
        return new DisplayStateResponse.NextGroupEntry(
                group.serviceId(),
                group.serviceNames(),
                group.tokens().stream().map(t -> new DisplayStateResponse.NextTicketEntry(t.tokenNumber(), t.position())).toList());
    }

    private static DisplayStateResponse.NoticeEntry noticeEntry(Notice notice) {
        return new DisplayStateResponse.NoticeEntry(notice.id(), notice.type(), notice.contentI18n(), notice.sortOrder());
    }

    private static DisplayStateResponse.SummaryEntry summaryEntry(DisplayStateReads.SummaryRow row) {
        return new DisplayStateResponse.SummaryEntry(
                row.serviceId(), row.serviceNames(), row.waitingCount(), row.estimate().low(), row.estimate().high());
    }

    static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
