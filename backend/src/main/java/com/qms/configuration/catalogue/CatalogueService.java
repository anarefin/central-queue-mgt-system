package com.qms.configuration.catalogue;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
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
 * Service groups, services, counter links and outcome codes (FR-CFG-010..015, FR-AGT-032, FR-AGT-033). Every method
 * needs {@code config:service_catalogue}, enforced here at the service layer (API-016) and limited to the caller's
 * sites (FR-CFG-106). Every change writes an audit entry with before and after values (FR-SEC-040).
 *
 * <p>Nothing is soft-deleted silently: deactivating a group deactivates its services, and reactivating a parent does
 * not bring the children back. A service is deleted only when no ticket refers to it (FR-CFG-015).
 */
@Service
@Profile(Profiles.SERVING)
public class CatalogueService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final CatalogueRepository repository;
    private final TeamRepository teams;
    private final ServiceUsage usage;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Clock clock;

    CatalogueService(CatalogueRepository repository, TeamRepository teams, ServiceUsage usage, AuditWriter audit, ScopeGuard scope, Clock clock) {
        this.repository = repository;
        this.teams = teams;
        this.usage = usage;
        this.audit = audit;
        this.scope = scope;
        this.clock = clock;
    }

    // ---- service groups ----------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<ServiceGroup> groups(UUID siteId) {
        scope.requireSite(siteId);
        requireSite(siteId);
        return repository.groupsOfSite(siteId);
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public ServiceGroup group(UUID id) {
        ServiceGroup group = requireGroup(id);
        scope.requireSite(group.siteId());
        return group;
    }

    /** A new group gets its one team at the same time. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceGroup createGroup(UUID siteId, CreateServiceGroupRequest request) {
        scope.requireSite(siteId);
        var site = requireSite(siteId);
        requireActiveParent(site.active());
        Instant now = clock.instant();
        Map<String, String> names = CatalogueRules.names("name_i18n", request.nameI18n(), site.defaultLanguage(), site.enabled());
        ServiceGroup group = new ServiceGroup(
                UUID.randomUUID(),
                siteId,
                names,
                CatalogueRules.missing(names, site.enabled()),
                CatalogueRules.tokenPrefix(request.tokenPrefix()),
                CatalogueRules.displayOrder(request.displayOrder()),
                true,
                now,
                now);
        repository.insert(group);
        UUID teamId = teams.insert(group.id(), names.get(site.defaultLanguage()));
        audit.record(AuditEvent.of("service_group.created", "service_group", group.id()).withAfter(snapshot(group)));
        audit.record(AuditEvent.of("team.created", "team", teamId).withAfter(Map.of("service_group_id", group.id().toString())));
        return group;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceGroup updateGroup(UUID id, UpdateServiceGroupRequest change) {
        ServiceGroup before = requireGroup(id);
        scope.requireSite(before.siteId());
        var site = requireSite(before.siteId());
        Map<String, String> names = change.nameI18n() == null
                ? before.nameI18n()
                : CatalogueRules.names("name_i18n", change.nameI18n(), site.defaultLanguage(), site.enabled());
        ServiceGroup after = new ServiceGroup(
                id,
                before.siteId(),
                names,
                CatalogueRules.missing(names, site.enabled()),
                change.tokenPrefix() == null ? before.tokenPrefix() : CatalogueRules.tokenPrefix(change.tokenPrefix()),
                change.displayOrder() == null ? before.displayOrder() : CatalogueRules.displayOrder(change.displayOrder()),
                before.active(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        if (names.containsKey(site.defaultLanguage())) teams.rename(id, names.get(site.defaultLanguage()));
        audit.record(AuditEvent.of("service_group.updated", "service_group", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceGroup deactivateGroup(UUID id, String reason) {
        ServiceGroup group = requireGroup(id);
        scope.requireSite(group.siteId());
        if (!group.active()) return group;
        Instant now = clock.instant();
        repository.setGroupActive(id, false, now);
        audit.record(activeFlag("service_group.deactivated", "service_group", id, false).withReason(reason));
        for (UUID serviceId : repository.activeServiceIdsOfGroup(id)) {
            repository.setServiceActive(serviceId, false, now);
            audit.record(activeFlag("service.deactivated", "service", serviceId, false).withReason("parent service group deactivated"));
        }
        return requireGroup(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceGroup activateGroup(UUID id) {
        ServiceGroup group = requireGroup(id);
        scope.requireSite(group.siteId());
        if (group.active()) return group;
        requireActiveParent(requireSite(group.siteId()).active());
        repository.setGroupActive(id, true, clock.instant());
        audit.record(activeFlag("service_group.activated", "service_group", id, true));
        return requireGroup(id);
    }

    // ---- services ----------------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<ServiceEntry> services(UUID groupId) {
        scope.requireSite(requireGroup(groupId).siteId());
        return repository.servicesOfGroup(groupId);
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public ServiceEntry service(UUID id) {
        ServiceEntry service = requireService(id);
        scope.requireSite(service.siteId());
        return service;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceEntry createService(UUID groupId, CreateServiceRequest request) {
        ServiceGroup group = requireGroup(groupId);
        scope.requireSite(group.siteId());
        requireActiveParent(group.active());
        var site = requireSite(group.siteId());
        Instant now = clock.instant();
        Map<String, String> names = CatalogueRules.names("name_i18n", request.nameI18n(), site.defaultLanguage(), site.enabled());
        ServiceEntry service = new ServiceEntry(
                UUID.randomUUID(),
                groupId,
                group.siteId(),
                names,
                CatalogueRules.missing(names, site.enabled()),
                CatalogueRules.tokenPrefix(request.tokenPrefix()),
                CatalogueRules.minutes("expected_minutes", request.expectedMinutes()),
                CatalogueRules.minutes("sla_wait_minutes", request.slaWaitMinutes()),
                CatalogueRules.channels(request.channels()),
                CatalogueRules.optional("icon", request.icon(), 50),
                CatalogueRules.displayOrder(request.displayOrder()),
                request.visitorIdentifier() == null ? "not_required" : CatalogueRules.choice("visitor_identifier", request.visitorIdentifier(), CatalogueRules.VISITOR_IDENTIFIER),
                request.bookingMode() == null ? "both" : CatalogueRules.choice("booking_mode", request.bookingMode(), CatalogueRules.BOOKING_MODE),
                true,
                now,
                now);
        repository.insert(service);
        audit.record(AuditEvent.of("service.created", "service", service.id()).withAfter(snapshot(service)));
        return service;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceEntry updateService(UUID id, UpdateServiceRequest change) {
        ServiceEntry before = requireService(id);
        scope.requireSite(before.siteId());
        var site = requireSite(before.siteId());
        Map<String, String> names = change.nameI18n() == null
                ? before.nameI18n()
                : CatalogueRules.names("name_i18n", change.nameI18n(), site.defaultLanguage(), site.enabled());
        ServiceEntry after = new ServiceEntry(
                id,
                before.serviceGroupId(),
                before.siteId(),
                names,
                CatalogueRules.missing(names, site.enabled()),
                change.tokenPrefix() == null ? before.tokenPrefix() : CatalogueRules.tokenPrefix(change.tokenPrefix()),
                change.expectedMinutes() == null ? before.expectedMinutes() : CatalogueRules.minutes("expected_minutes", change.expectedMinutes()),
                change.slaWaitMinutes() == null ? before.slaWaitMinutes() : CatalogueRules.minutes("sla_wait_minutes", change.slaWaitMinutes()),
                change.channels() == null ? before.channels() : CatalogueRules.channels(change.channels()),
                change.icon() == null ? before.icon() : CatalogueRules.optional("icon", change.icon(), 50),
                change.displayOrder() == null ? before.displayOrder() : CatalogueRules.displayOrder(change.displayOrder()),
                change.visitorIdentifier() == null ? before.visitorIdentifier() : CatalogueRules.choice("visitor_identifier", change.visitorIdentifier(), CatalogueRules.VISITOR_IDENTIFIER),
                change.bookingMode() == null ? before.bookingMode() : CatalogueRules.choice("booking_mode", change.bookingMode(), CatalogueRules.BOOKING_MODE),
                before.active(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("service.updated", "service", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceEntry deactivateService(UUID id, String reason) {
        ServiceEntry service = requireService(id);
        scope.requireSite(service.siteId());
        if (!service.active()) return service;
        repository.setServiceActive(id, false, clock.instant());
        audit.record(activeFlag("service.deactivated", "service", id, false).withReason(reason));
        return requireService(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ServiceEntry activateService(UUID id) {
        ServiceEntry service = requireService(id);
        scope.requireSite(service.siteId());
        if (service.active()) return service;
        requireActiveParent(requireGroup(service.serviceGroupId()).active());
        repository.setServiceActive(id, true, clock.instant());
        audit.record(activeFlag("service.activated", "service", id, true));
        return requireService(id);
    }

    /**
     * Deleting is refused once any ticket refers to the service; only deactivation is left (FR-CFG-015). A service
     * nobody ever used goes together with its counter links and outcome codes.
     */
    @PreAuthorize(PERMISSION)
    @Transactional
    public void deleteService(UUID id) {
        ServiceEntry service = requireService(id);
        scope.requireSite(service.siteId());
        if (usage.hasTickets(id)) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "service_has_tickets"));
        repository.deleteLinksOfService(id);
        repository.deleteOutcomesOfService(id);
        repository.deleteService(id);
        audit.record(AuditEvent.of("service.deleted", "service", id).withBefore(snapshot(service)));
    }

    // ---- counter links -----------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<CounterLink> links(UUID serviceId) {
        scope.requireSite(requireService(serviceId).siteId());
        return repository.links(serviceId);
    }

    /** The active counters of the group's site, from which a link can be made. */
    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<CounterOption> counterOptions(UUID groupId) {
        ServiceGroup group = requireGroup(groupId);
        scope.requireSite(group.siteId());
        return repository.counterOptions(group.siteId());
    }

    /** Links a counter to a service, or changes the weight of an existing link (FR-CFG-011). */
    @PreAuthorize(PERMISSION)
    @Transactional
    public CounterLink linkCounter(UUID serviceId, UUID counterId, Integer preferenceWeight) {
        ServiceEntry service = requireService(serviceId);
        scope.requireSite(service.siteId());
        int weight = CatalogueRules.preferenceWeight(preferenceWeight);
        var counter = repository.counter(counterId).orElseThrow(() -> CatalogueRules.invalid("counter_id", "unknown_counter"));
        if (!counter.siteId().equals(service.siteId())) throw CatalogueRules.invalid("counter_id", "different_site");
        var existing = repository.link(serviceId, counterId);
        if (existing.isEmpty()) {
            requireActiveParent(service.active());
            requireActiveParent(counter.active());
        }
        if (existing.isPresent() && existing.get().preferenceWeight() == weight) return existing.get();
        repository.upsertLink(counterId, serviceId, weight);
        Map<String, Object> after = Map.of("counter_id", counterId.toString(), "preference_weight", weight);
        if (existing.isEmpty()) {
            audit.record(AuditEvent.of("service.counter_linked", "service", serviceId).withAfter(after));
        } else {
            audit.record(AuditEvent.of("service.counter_link_updated", "service", serviceId)
                    .withBefore(Map.of("counter_id", counterId.toString(), "preference_weight", existing.get().preferenceWeight()))
                    .withAfter(after));
        }
        return repository.link(serviceId, counterId).orElseThrow();
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public void unlinkCounter(UUID serviceId, UUID counterId) {
        scope.requireSite(requireService(serviceId).siteId());
        CounterLink link = repository.link(serviceId, counterId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        repository.deleteLink(counterId, serviceId);
        audit.record(AuditEvent.of("service.counter_unlinked", "service", serviceId)
                .withBefore(Map.of("counter_id", counterId.toString(), "preference_weight", link.preferenceWeight())));
    }

    // ---- outcome codes -----------------------------------------------------------------------------------------

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<OutcomeCode> outcomes(UUID serviceId) {
        scope.requireSite(requireService(serviceId).siteId());
        return repository.outcomesOfService(serviceId);
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public OutcomeCode outcome(UUID id) {
        OutcomeCode outcome = requireOutcome(id);
        scope.requireSite(outcome.siteId());
        return outcome;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public OutcomeCode createOutcome(UUID serviceId, CreateOutcomeCodeRequest request) {
        ServiceEntry service = requireService(serviceId);
        scope.requireSite(service.siteId());
        requireActiveParent(service.active());
        var site = requireSite(service.siteId());
        Instant now = clock.instant();
        Map<String, String> labels = CatalogueRules.names("label_i18n", request.labelI18n(), site.defaultLanguage(), site.enabled());
        OutcomeCode outcome = new OutcomeCode(
                UUID.randomUUID(),
                serviceId,
                service.siteId(),
                CatalogueRules.outcomeCode(request.code()),
                labels,
                CatalogueRules.missing(labels, site.enabled()),
                CatalogueRules.displayOrder(request.displayOrder()),
                true,
                now,
                now);
        try {
            repository.insert(outcome);
        } catch (DuplicateKeyException taken) {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("field", "code"));
        }
        audit.record(AuditEvent.of("outcome_code.created", "outcome_code", outcome.id()).withAfter(snapshot(outcome)));
        return outcome;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public OutcomeCode updateOutcome(UUID id, UpdateOutcomeCodeRequest change) {
        OutcomeCode before = requireOutcome(id);
        scope.requireSite(before.siteId());
        var site = requireSite(before.siteId());
        Map<String, String> labels = change.labelI18n() == null
                ? before.labelI18n()
                : CatalogueRules.names("label_i18n", change.labelI18n(), site.defaultLanguage(), site.enabled());
        OutcomeCode after = new OutcomeCode(
                id,
                before.serviceId(),
                before.siteId(),
                before.code(),
                labels,
                CatalogueRules.missing(labels, site.enabled()),
                change.displayOrder() == null ? before.displayOrder() : CatalogueRules.displayOrder(change.displayOrder()),
                before.active(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("outcome_code.updated", "outcome_code", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return after;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public OutcomeCode deactivateOutcome(UUID id, String reason) {
        OutcomeCode outcome = requireOutcome(id);
        scope.requireSite(outcome.siteId());
        if (!outcome.active()) return outcome;
        repository.setOutcomeActive(id, false, clock.instant());
        audit.record(activeFlag("outcome_code.deactivated", "outcome_code", id, false).withReason(reason));
        return requireOutcome(id);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public OutcomeCode activateOutcome(UUID id) {
        OutcomeCode outcome = requireOutcome(id);
        scope.requireSite(outcome.siteId());
        if (outcome.active()) return outcome;
        requireActiveParent(requireService(outcome.serviceId()).active());
        repository.setOutcomeActive(id, true, clock.instant());
        audit.record(activeFlag("outcome_code.activated", "outcome_code", id, true));
        return requireOutcome(id);
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private static AuditEvent activeFlag(String action, String entity, UUID id, boolean nowActive) {
        return AuditEvent.of(action, entity, id).withBefore(Map.of("active", !nowActive)).withAfter(Map.of("active", nowActive));
    }

    /** New or reactivated children need an active parent, so an active child never sits under an inactive one. */
    private static void requireActiveParent(boolean parentActive) {
        if (!parentActive) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "parent_inactive"));
    }

    private CatalogueRepository.SiteLanguages requireSite(UUID siteId) {
        return repository.siteLanguages(siteId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private ServiceGroup requireGroup(UUID id) {
        return repository.group(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private ServiceEntry requireService(UUID id) {
        return repository.service(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private OutcomeCode requireOutcome(UUID id) {
        return repository.outcome(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static Map<String, Object> snapshot(ServiceGroup group) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("site_id", group.siteId().toString());
        values.put("name_i18n", group.nameI18n());
        values.put("token_prefix", group.tokenPrefix());
        values.put("display_order", group.displayOrder());
        values.put("active", group.active());
        return values;
    }

    private static Map<String, Object> snapshot(ServiceEntry service) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("service_group_id", service.serviceGroupId().toString());
        values.put("name_i18n", service.nameI18n());
        values.put("token_prefix", service.tokenPrefix());
        values.put("expected_minutes", service.expectedMinutes());
        values.put("sla_wait_minutes", service.slaWaitMinutes());
        values.put("channels", service.channels());
        values.put("icon", service.icon());
        values.put("display_order", service.displayOrder());
        values.put("visitor_identifier", service.visitorIdentifier());
        values.put("booking_mode", service.bookingMode());
        values.put("active", service.active());
        return values;
    }

    private static Map<String, Object> snapshot(OutcomeCode outcome) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("service_id", outcome.serviceId().toString());
        values.put("code", outcome.code());
        values.put("label_i18n", outcome.labelI18n());
        values.put("display_order", outcome.displayOrder());
        values.put("active", outcome.active());
        return values;
    }
}
