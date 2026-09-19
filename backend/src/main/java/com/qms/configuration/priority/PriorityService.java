package com.qms.configuration.priority;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.priority.PriorityRepository.GroupRef;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.i18n.LanguageProperties;
import com.qms.platform.security.ScopeGuard;
import com.qms.queue.QueueStrategy;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Priority classes and the ordering strategy of each Service group (FR-QUE-010, FR-QUE-021). Changing either needs
 * {@code config:priority_routing}, enforced here at the service layer (API-016); a strategy is also limited to the
 * caller's sites and groups (FR-CFG-106). Every change writes an audit entry with before and after values (FR-SEC-040).
 * Reception may read the classes, to offer them at issue.
 *
 * <p>A change to a class or a strategy takes effect at the next read of the queue: order is computed, never stored, so
 * no waiting ticket is touched (ADR-0004). Classes are organisation-wide and are deactivated, never deleted, so tickets
 * that carry one keep resolving.
 */
@Service
@Profile(Profiles.SERVING)
public class PriorityService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_PRIORITY_ROUTING)";
    private static final String READ = "hasAnyAuthority(T(com.qms.platform.security.Authorities).CONFIG_PRIORITY_ROUTING,"
            + " T(com.qms.platform.security.Authorities).TICKET_ISSUE)";

    private final PriorityRepository repository;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final LanguageProperties languages;
    private final Clock clock;

    PriorityService(PriorityRepository repository, AuditWriter audit, ScopeGuard scope, LanguageProperties languages, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.scope = scope;
        this.languages = languages;
        this.clock = clock;
    }

    // ---- priority classes --------------------------------------------------------------------------------------

    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public List<PriorityClass> classes() {
        return repository.all();
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public PriorityClass create(PriorityClassRequest request) {
        Instant now = clock.instant();
        PriorityClass created = new PriorityClass(
                UUID.randomUUID(),
                PriorityRules.names(request.nameI18n(), languages.systemDefaultLanguage(), languages.languages()),
                PriorityRules.headstart(request.headstartMinutes()),
                PriorityRules.maxWait(request.maxWaitMinutes()),
                PriorityRules.prefix(request.tokenPrefixOverride()),
                false,
                true,
                now,
                now);
        repository.insert(created);
        audit.record(AuditEvent.of("priority_class.created", "priority_class", created.id()).withAfter(snapshot(created)));
        return created;
    }

    /** Replaces the editable content. The default class keeps its Head start of 0 and takes no prefix override. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public PriorityClass replace(UUID id, PriorityClassRequest request) {
        PriorityClass before = require(id);
        int headstart = PriorityRules.headstart(request.headstartMinutes());
        String prefix = PriorityRules.prefix(request.tokenPrefixOverride());
        if (before.isDefault() && headstart != 0) throw PriorityRules.invalid("headstart_minutes", "must_be_zero");
        if (before.isDefault() && prefix != null) throw PriorityRules.invalid("token_prefix_override", "not_allowed");
        PriorityClass after = new PriorityClass(
                id,
                PriorityRules.names(request.nameI18n(), languages.systemDefaultLanguage(), languages.languages()),
                headstart,
                PriorityRules.maxWait(request.maxWaitMinutes()),
                prefix,
                before.isDefault(),
                before.active(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("priority_class.updated", "priority_class", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return require(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public PriorityClass deactivate(UUID id, String reason) {
        PriorityClass current = require(id);
        if (current.isDefault()) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "default_priority_class"));
        if (!current.active()) return current;
        repository.setActive(id, false, clock.instant());
        audit.record(activeFlag("priority_class.deactivated", id, false).withReason(reason));
        return require(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public PriorityClass activate(UUID id) {
        PriorityClass current = require(id);
        if (current.active()) return current;
        repository.setActive(id, true, clock.instant());
        audit.record(activeFlag("priority_class.activated", id, true));
        return require(id);
    }

    // ---- strategy ----------------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public RoutingStrategyView strategy(UUID groupId) {
        GroupRef group = requireGroup(groupId);
        return view(group.id(), repository.strategy(groupId));
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public RoutingStrategyView setStrategy(UUID groupId, RoutingStrategyRequest request) {
        GroupRef group = requireGroup(groupId);
        QueueStrategy chosen = request == null || request.strategy() == null
                ? null
                : QueueStrategy.fromWire(request.strategy()).orElse(null);
        if (chosen == null) throw PriorityRules.invalid("strategy", "Pattern");
        Optional<String> stored = repository.strategy(groupId);
        String before = stored.orElse(QueueStrategy.DEFAULT.wire());
        if (stored.isPresent() && before.equals(chosen.wire())) return view(groupId, stored);
        repository.setStrategy(groupId, chosen.wire(), clock.instant());
        if (!before.equals(chosen.wire())) {
            audit.record(AuditEvent.of("routing_strategy.updated", "service_group", group.id())
                    .withBefore(Map.of("strategy", before))
                    .withAfter(Map.of("strategy", chosen.wire())));
        }
        return view(groupId, Optional.of(chosen.wire()));
    }

    // ---- defaults ----------------------------------------------------------------------------------------------

    /** The class each channel gives its tickets, and the Services that give their own, within the caller's sites. */
    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public PriorityDefaults defaults() {
        Map<String, UUID> channels = repository.channelDefaults();
        var sites = scope.sites(List.of());
        var groups = scope.groups(List.of());
        List<PriorityDefaults.Channel> channelViews = PriorityRules.CHANNELS.stream().map(c -> new PriorityDefaults.Channel(c, channels.get(c))).toList();
        List<PriorityDefaults.Service> serviceViews = repository.serviceDefaults().stream()
                .filter(v -> sites.isEmpty() || sites.contains(v.siteId()))
                .filter(v -> groups.isEmpty() || groups.contains(v.groupId()))
                .map(v -> new PriorityDefaults.Service(v.id(), v.defaultClassId()))
                .toList();
        return new PriorityDefaults(channelViews, serviceViews);
    }

    /**
     * Sets or, with no class, clears the class the tickets of an issuing channel get by default (FR-QUE-011). It applies to tickets
     * issued from now on and never moves one already issued (FR-CFG-041). Audited with before and after values (FR-SEC-040).
     */
    @PreAuthorize(PERMISSION)
    @Transactional
    public PriorityDefaults.Channel setChannelDefault(String channel, PriorityDefaultRequest request) {
        if (!PriorityRules.CHANNELS.contains(channel)) throw new ApiException(ErrorCode.NOT_FOUND);
        UUID classId = defaultClass(request);
        UUID before = repository.channelDefaults().get(channel);
        if (!java.util.Objects.equals(before, classId)) {
            if (classId == null) repository.clearChannelDefault(channel);
            else repository.setChannelDefault(channel, classId, clock.instant());
            audit.record(AuditEvent.of("priority_default.channel_updated", "channel", null)
                    .withBefore(defaultSnapshot("channel", channel, before))
                    .withAfter(defaultSnapshot("channel", channel, classId)));
        }
        return new PriorityDefaults.Channel(channel, classId);
    }

    /** As {@link #setChannelDefault} for one Service, which is limited to the caller's sites and groups (FR-CFG-106). */
    @PreAuthorize(PERMISSION)
    @Transactional
    public PriorityDefaults.Service setServiceDefault(UUID serviceId, PriorityDefaultRequest request) {
        PriorityRepository.ServiceRef service = repository.service(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(service.siteId());
        scope.requireGroup(service.groupId());
        UUID classId = defaultClass(request);
        if (!java.util.Objects.equals(service.defaultClassId(), classId)) {
            repository.setServiceDefault(serviceId, classId, clock.instant());
            audit.record(AuditEvent.of("priority_default.service_updated", "service", serviceId)
                    .withBefore(defaultSnapshot("service", serviceId.toString(), service.defaultClassId()))
                    .withAfter(defaultSnapshot("service", serviceId.toString(), classId)));
        }
        return new PriorityDefaults.Service(serviceId, classId);
    }

    /** The class a default names: one that exists and is active, or none to clear the default. */
    private UUID defaultClass(PriorityDefaultRequest request) {
        UUID id = request == null ? null : request.priorityClassId();
        if (id == null) return null;
        PriorityClass chosen = repository.find(id).orElseThrow(() -> PriorityRules.invalid("priority_class_id", "not_found"));
        if (!chosen.active()) throw PriorityRules.invalid("priority_class_id", "inactive");
        return id;
    }

    private static Map<String, Object> defaultSnapshot(String key, String value, UUID classId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        values.put("priority_class_id", classId == null ? null : classId.toString());
        return values;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static RoutingStrategyView view(UUID groupId, Optional<String> stored) {
        return new RoutingStrategyView(groupId, stored.orElse(QueueStrategy.DEFAULT.wire()), stored.isEmpty(), QueueStrategy.wires());
    }

    private PriorityClass require(UUID id) {
        return repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private GroupRef requireGroup(UUID groupId) {
        GroupRef group = repository.group(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(group.siteId());
        scope.requireGroup(group.id());
        return group;
    }

    private static AuditEvent activeFlag(String action, UUID id, boolean active) {
        return AuditEvent.of(action, "priority_class", id).withBefore(Map.of("active", !active)).withAfter(Map.of("active", active));
    }

    private static Map<String, Object> snapshot(PriorityClass c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name_i18n", c.nameI18n());
        values.put("headstart_minutes", c.headstartMinutes());
        values.put("max_wait_minutes", c.maxWaitMinutes());
        values.put("token_prefix_override", c.tokenPrefixOverride());
        values.put("is_default", c.isDefault());
        values.put("active", c.active());
        return values;
    }
}
