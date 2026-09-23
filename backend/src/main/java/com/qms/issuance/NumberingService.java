package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.versioning.ConfigVersion;
import com.qms.configuration.versioning.ConfigVersionCodec;
import com.qms.configuration.versioning.ConfigVersionView;
import com.qms.configuration.versioning.ConfigVersions;
import com.qms.issuance.NumberingRepository.ActiveService;
import com.qms.issuance.NumberingRepository.Scope;
import com.qms.issuance.TokenNumbering.Period;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.devices.DeviceConfigNotifier;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * Administration of token numbering rules and the preview of the next Token number (FR-CFG-018). Every method needs
 * {@code config:service_catalogue}, enforced here (API-016) and limited to the caller's sites (FR-CFG-106). Every
 * change writes an audit entry with before and after values (FR-SEC-040).
 *
 * <p>A change only shapes tickets issued after it (FR-CFG-041): nothing here writes a ticket, and the response says how
 * many waiting tickets keep the numbers they have.
 */
@Service
@Profile(Profiles.SERVING)
public class NumberingService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";
    static final String DEFAULT_SOURCE = "default";
    static final String ENTITY = "numbering_rule";

    private final NumberingRepository repository;
    private final NumberingResets resets;
    private final SequenceBlocks sequences;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final ConfigVersions versions;
    private final Optional<DeviceConfigNotifier> deviceNotifier;
    private final Clock clock;

    NumberingService(
            NumberingRepository repository,
            NumberingResets resets,
            SequenceBlocks sequences,
            AuditWriter audit,
            ScopeGuard scope,
            ConfigVersions versions,
            Optional<DeviceConfigNotifier> deviceNotifier,
            Clock clock) {
        this.repository = repository;
        this.resets = resets;
        this.sequences = sequences;
        this.audit = audit;
        this.scope = scope;
        this.versions = versions;
        this.deviceNotifier = deviceNotifier;
        this.clock = clock;
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<NumberingRuleView> rules(UUID siteId) {
        scope.requireSite(siteId);
        return repository.rulesOfSite(siteId).stream().map(NumberingRuleView::of).toList();
    }

    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public NumberingRuleView rule(String scopeType, UUID scopeId) {
        requireScope(scopeType, scopeId);
        return NumberingRuleView.of(repository.rule(scopeType, scopeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    /** Whether a scope already carries its own rule (never a group's inherited one), so a caller seeding a default
     * (ticket 67) can leave an admin's own existing rule exactly as it is rather than overwrite it. */
    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public boolean hasRule(String scopeType, UUID scopeId) {
        requireScope(scopeType, scopeId);
        return repository.rule(scopeType, scopeId).isPresent();
    }

    /** Sets the rule of a Service or Service group, replacing the one it had. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public NumberingRuleChange setRule(String scopeType, UUID scopeId, NumberingRuleRequest request) {
        Scope where = requireScope(scopeType, scopeId);
        NumberingSpec spec = NumberingRules.parse(request);
        Instant now = clock.instant();
        Optional<NumberingRule> before = repository.rule(scopeType, scopeId);
        NumberingRule after;
        if (before.isEmpty()) {
            after = new NumberingRule(UUID.randomUUID(), where.siteId(), scopeType, scopeId, spec, now, now);
            repository.insert(after);
            audit.record(AuditEvent.of("numbering_rule.created", "numbering_rule", after.id()).withAfter(snapshot(after)));
            versions.record(ENTITY, scopeId, snapshot(after));
            deviceNotifier.ifPresent(n -> n.notifySite(where.siteId()));
        } else if (before.get().spec().equals(spec)) {
            after = before.get();
        } else {
            after = new NumberingRule(before.get().id(), where.siteId(), scopeType, scopeId, spec, before.get().createdAt(), now);
            repository.update(after);
            audit.record(AuditEvent.of("numbering_rule.updated", "numbering_rule", after.id()).withBefore(snapshot(before.get())).withAfter(snapshot(after)));
            versions.record(ENTITY, scopeId, snapshot(after));
            deviceNotifier.ifPresent(n -> n.notifySite(where.siteId()));
        }
        return new NumberingRuleChange(NumberingRuleView.of(after), repository.waitingTickets(scopeType, scopeId));
    }

    /** Removes the rule, so the scope goes back to its group's rule or the default. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public NumberingRuleChange removeRule(String scopeType, UUID scopeId) {
        Scope where = requireScope(scopeType, scopeId);
        NumberingRule rule = repository.rule(scopeType, scopeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        repository.delete(rule.id());
        audit.record(AuditEvent.of("numbering_rule.deleted", "numbering_rule", rule.id()).withBefore(snapshot(rule)));
        versions.record(ENTITY, scopeId, Map.of("deleted", true, "scope_type", scopeType, "scope_id", scopeId.toString()));
        deviceNotifier.ifPresent(n -> n.notifySite(where.siteId()));
        return new NumberingRuleChange(null, repository.waitingTickets(scopeType, scopeId));
    }

    /** History of one scope's numbering rule (FR-CFG-040), newest first; survives the rule being removed and re-created. */
    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public List<ConfigVersionView> versions(String scopeType, UUID scopeId) {
        requireScope(scopeType, scopeId);
        return versions.history(ENTITY, scopeId);
    }

    /** Reverts a scope's numbering rule to a prior version, including a version that recorded its removal (FR-CFG-040). */
    @PreAuthorize(PERMISSION)
    @Transactional
    public NumberingRuleChange revert(String scopeType, UUID scopeId, UUID versionId) {
        requireScope(scopeType, scopeId);
        ConfigVersion version = versions.find(ENTITY, versionId).filter(v -> v.entityId().equals(scopeId)).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Map<String, Object> payload = version.payload();
        if (ConfigVersionCodec.asBoolean(payload.get("deleted"))) {
            return repository.rule(scopeType, scopeId).isPresent() ? removeRule(scopeType, scopeId) : new NumberingRuleChange(null, repository.waitingTickets(scopeType, scopeId));
        }
        NumberingRuleRequest request = new NumberingRuleRequest(
                ConfigVersionCodec.asString(payload.get("prefix_source")),
                ConfigVersionCodec.asString(payload.get("fixed_prefix")),
                ConfigVersionCodec.asLong(payload.get("sequence_start")),
                ConfigVersionCodec.asInt(payload.get("padding")),
                ConfigVersionCodec.asString(payload.get("reset_boundary")),
                ConfigVersionCodec.asString(payload.get("reset_time")),
                ConfigVersionCodec.asString(payload.get("separator")));
        return setRule(scopeType, scopeId, request);
    }

    /**
     * The Token number the next ticket of each Service in the scope would get, under the rule that applies now. Nothing
     * is issued and no number is used: a Service group previews each of its active Services.
     */
    @PreAuthorize(PERMISSION)
    @Transactional(readOnly = true)
    public NumberingPreview preview(String scopeType, UUID scopeId) {
        requireScope(scopeType, scopeId);
        List<ActiveService> services = NumberingRule.SERVICE.equals(scopeType)
                ? List.of(repository.service(scopeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)))
                : repository.activeServicesOfGroup(scopeId);
        Instant now = clock.instant();
        List<NumberingPreview.Item> items = new ArrayList<>();
        for (ActiveService service : services) items.add(previewOf(service, now));
        return new NumberingPreview(items);
    }

    private NumberingPreview.Item previewOf(ActiveService service, Instant now) {
        Optional<NumberingRule> own = repository.rule(NumberingRule.SERVICE, service.serviceId());
        Optional<NumberingRule> inherited = own.isPresent() ? own : repository.rule(NumberingRule.SERVICE_GROUP, service.groupId());
        NumberingSpec spec = inherited.map(NumberingRule::spec).orElse(NumberingSpec.DEFAULT);
        String source = own.isPresent() ? NumberingRule.SERVICE : inherited.isPresent() ? NumberingRule.SERVICE_GROUP : DEFAULT_SOURCE;

        String prefix = spec.prefix(service.servicePrefix(), service.groupPrefix());
        Period period = TokenNumbering.period(now, ZoneId.of(service.timezone()), spec.boundary(), spec.resetTime());
        long sequence = sequences.peek(service.siteId(), prefix, period.key())
                .orElseGet(() -> resets.firstValue(SequenceBlocks.scope(service.siteId(), prefix), period, spec));
        return new NumberingPreview.Item(service.serviceId(), spec.format(prefix, sequence), prefix, sequence, period.key(), period.end(), source);
    }

    /** The scope must exist, and its site must be one the caller may configure. */
    private Scope requireScope(String scopeType, UUID scopeId) {
        Optional<Scope> found = NumberingRule.SERVICE.equals(scopeType) ? repository.serviceScope(scopeId) : repository.groupScope(scopeId);
        Scope where = found.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        scope.requireSite(where.siteId());
        return where;
    }

    private static Map<String, Object> snapshot(NumberingRule rule) {
        NumberingRuleView view = NumberingRuleView.of(rule);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scope_type", view.scopeType());
        values.put("scope_id", view.scopeId().toString());
        values.put("prefix_source", view.prefixSource());
        values.put("fixed_prefix", view.fixedPrefix());
        values.put("sequence_start", view.sequenceStart());
        values.put("padding", view.padding());
        values.put("reset_boundary", view.resetBoundary());
        values.put("reset_time", view.resetTime());
        values.put("separator", view.separator());
        return values;
    }
}
