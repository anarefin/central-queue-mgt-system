package com.qms.configuration.breaks;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.i18n.LanguageProperties;
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
 * Break types (FR-AGT-020): what an Agent may pick when they press F9, each with names in every installed language and an
 * optional maximum duration. Changing them is organisation-wide configuration and needs {@code config:org_sites_zones},
 * enforced here at the service layer (API-016); an Agent may read them to choose one. Every change writes an audit entry
 * with before and after values (FR-SEC-040). A change never touches a break already recorded: the record keeps pointing at
 * the type, and the type is deactivated rather than deleted.
 */
@Service
@Profile(Profiles.SERVING)
public class BreakTypeService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    /** An Agent (or admin) who runs a counter session reads the list to start a break. */
    static final String READ = "hasAnyAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES,"
            + " T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE,"
            + " T(com.qms.platform.security.Authorities).COUNTER_SESSION_OPEN_CLOSE + ':own')";

    private final BreakTypeRepository repository;
    private final AuditWriter audit;
    private final LanguageProperties languages;
    private final Clock clock;

    BreakTypeService(BreakTypeRepository repository, AuditWriter audit, LanguageProperties languages, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.languages = languages;
        this.clock = clock;
    }

    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public List<BreakType> types() {
        return repository.all();
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public BreakType create(BreakTypeRequest request) {
        Instant now = clock.instant();
        BreakType created = new BreakType(
                UUID.randomUUID(),
                BreakTypeRules.names(request.nameI18n(), languages.systemDefaultLanguage(), languages.languages()),
                BreakTypeRules.maxMinutes(request.maxMinutes()),
                true,
                now,
                now);
        repository.insert(created);
        audit.record(AuditEvent.of("break_type.created", "break_type", created.id()).withAfter(snapshot(created)));
        return created;
    }

    /** Replaces the editable content; changing nothing writes nothing. */
    @PreAuthorize(MANAGE)
    @Transactional
    public BreakType replace(UUID id, BreakTypeRequest request) {
        BreakType before = require(id);
        BreakType after = new BreakType(
                id,
                BreakTypeRules.names(request.nameI18n(), languages.systemDefaultLanguage(), languages.languages()),
                BreakTypeRules.maxMinutes(request.maxMinutes()),
                before.active(),
                before.createdAt(),
                clock.instant());
        if (snapshot(after).equals(snapshot(before))) return before;
        repository.update(after);
        audit.record(AuditEvent.of("break_type.updated", "break_type", id).withBefore(snapshot(before)).withAfter(snapshot(after)));
        return require(id);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public BreakType deactivate(UUID id, String reason) {
        BreakType current = require(id);
        if (!current.active()) return current;
        repository.setActive(id, false, clock.instant());
        audit.record(activeFlag("break_type.deactivated", id, false).withReason(reason));
        return require(id);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public BreakType activate(UUID id) {
        BreakType current = require(id);
        if (current.active()) return current;
        repository.setActive(id, true, clock.instant());
        audit.record(activeFlag("break_type.activated", id, true));
        return require(id);
    }

    private BreakType require(UUID id) {
        return repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static AuditEvent activeFlag(String action, UUID id, boolean active) {
        return AuditEvent.of(action, "break_type", id).withBefore(Map.of("active", !active)).withAfter(Map.of("active", active));
    }

    private static Map<String, Object> snapshot(BreakType t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name_i18n", t.nameI18n());
        values.put("max_minutes", t.maxMinutes());
        values.put("active", t.active());
        return values;
    }
}
