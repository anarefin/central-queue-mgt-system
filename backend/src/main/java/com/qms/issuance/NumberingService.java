package com.qms.issuance;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.issuance.NumberingRepository.ActiveService;
import com.qms.issuance.NumberingRepository.Scope;
import com.qms.issuance.TokenNumbering.Period;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
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

    private final NumberingRepository repository;
    private final NumberingResets resets;
    private final SequenceBlocks sequences;
    private final AuditWriter audit;
    private final ScopeGuard scope;
    private final Clock clock;

    NumberingService(NumberingRepository repository, NumberingResets resets, SequenceBlocks sequences, AuditWriter audit, ScopeGuard scope, Clock clock) {
        this.repository = repository;
        this.resets = resets;
        this.sequences = sequences;
        this.audit = audit;
        this.scope = scope;
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
        } else if (before.get().spec().equals(spec)) {
            after = before.get();
        } else {
            after = new NumberingRule(before.get().id(), where.siteId(), scopeType, scopeId, spec, before.get().createdAt(), now);
            repository.update(after);
            audit.record(AuditEvent.of("numbering_rule.updated", "numbering_rule", after.id()).withBefore(snapshot(before.get())).withAfter(snapshot(after)));
        }
        return new NumberingRuleChange(NumberingRuleView.of(after), repository.waitingTickets(scopeType, scopeId));
    }

    /** Removes the rule, so the scope goes back to its group's rule or the default. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public NumberingRuleChange removeRule(String scopeType, UUID scopeId) {
        requireScope(scopeType, scopeId);
        NumberingRule rule = repository.rule(scopeType, scopeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        repository.delete(rule.id());
        audit.record(AuditEvent.of("numbering_rule.deleted", "numbering_rule", rule.id()).withBefore(snapshot(rule)));
        return new NumberingRuleChange(null, repository.waitingTickets(scopeType, scopeId));
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
