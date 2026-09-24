package com.qms.configuration.catalogue;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Journey templates (ticket 31, FR-QUE-060): an ordered or unordered set of Service stops, scoped to one Service group,
 * that Reception can issue in one action at {@code POST /journeys}. Never deleted, only deactivated, the same as an
 * outcome code (FR-AGT-033's pattern).
 */
@Service
@Profile(Profiles.SERVING)
public class JourneyTemplateService {

    private static final int MIN_STOPS = 2;

    private final JourneyTemplateRepository repository;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Clock clock;

    JourneyTemplateService(JourneyTemplateRepository repository, AuditWriter audit, ScopeGuard scope, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<JourneyTemplate> ofGroup(UUID groupId) {
        JourneyTemplateRepository.GroupInfo group = requireGroup(groupId);
        scope.requireSite(group.siteId());
        return repository.ofGroup(groupId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public JourneyTemplate get(UUID id) {
        JourneyTemplateRepository.Row row = requireTemplate(id);
        scope.requireSite(requireGroup(row.serviceGroupId()).siteId());
        return view(row);
    }

    @Transactional
    public JourneyTemplate create(UUID groupId, CreateJourneyTemplateRequest request) {
        JourneyTemplateRepository.GroupInfo group = requireGroup(groupId);
        scope.requireSite(group.siteId());
        if (!group.active()) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "parent_inactive"));
        if (request == null || request.nameI18n() == null || request.nameI18n().isEmpty()) throw invalid("name_i18n", "required");
        if (request.serviceIds() == null || request.serviceIds().size() < MIN_STOPS) throw invalid("service_ids", "min_two");
        if (!repository.allBelongToGroupAndActive(groupId, request.serviceIds())) throw invalid("service_ids", "invalid");

        UUID id = repository.insert(groupId, request.nameI18n(), request.ordered(), request.displayOrder() == null ? 0 : request.displayOrder(), request.serviceIds(), clock.instant());
        JourneyTemplate created = view(requireTemplate(id));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("service_group_id", groupId.toString());
        after.put("name_i18n", request.nameI18n());
        after.put("ordered", request.ordered());
        after.put("service_ids", request.serviceIds().stream().map(UUID::toString).toList());
        audit.record(AuditEvent.of("journey_template.created", "journey_template", id).withAfter(after));
        return created;
    }

    @Transactional
    public JourneyTemplate deactivate(UUID id) {
        JourneyTemplateRepository.Row row = requireTemplate(id);
        scope.requireSite(requireGroup(row.serviceGroupId()).siteId());
        if (!row.active()) return view(row);
        repository.setActive(id, false);
        audit.record(AuditEvent.of("journey_template.deactivated", "journey_template", id).withAfter(Map.of("active", false)));
        return get(id);
    }

    @Transactional
    public JourneyTemplate activate(UUID id) {
        JourneyTemplateRepository.Row row = requireTemplate(id);
        JourneyTemplateRepository.GroupInfo group = requireGroup(row.serviceGroupId());
        scope.requireSite(group.siteId());
        if (row.active()) return view(row);
        if (!group.active()) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "parent_inactive"));
        repository.setActive(id, true);
        audit.record(AuditEvent.of("journey_template.activated", "journey_template", id).withAfter(Map.of("active", true)));
        return get(id);
    }

    private JourneyTemplate view(JourneyTemplateRepository.Row row) {
        List<JourneyTemplate.Stop> stops = repository.stopsOf(row.id()).stream().map(s -> new JourneyTemplate.Stop(s.seq(), s.serviceId(), s.serviceNames())).toList();
        return new JourneyTemplate(row.id(), row.serviceGroupId(), row.nameI18n(), row.ordered(), row.displayOrder(), row.active(), stops);
    }

    private JourneyTemplateRepository.Row requireTemplate(UUID id) {
        return repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private JourneyTemplateRepository.GroupInfo requireGroup(UUID groupId) {
        return repository.group(groupId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
