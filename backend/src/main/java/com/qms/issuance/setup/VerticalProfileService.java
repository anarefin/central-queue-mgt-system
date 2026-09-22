package com.qms.issuance.setup;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.priority.PriorityClass;
import com.qms.configuration.priority.PriorityClassRequest;
import com.qms.configuration.priority.PriorityService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a vertical profile (SRS §3, ticket 56). Every profile's content (labels, starter catalogue, priority
 * classes, numbering defaults, report/KPI defaults, feature flags) is data read by {@link VerticalProfileCatalog};
 * nothing here branches on which profile it is (CFG-001).
 *
 * <p>{@code apply} only ever runs once, at first-run setup (CFG-002): once a profile is active, further changes go
 * through the explicit {@code reset} action, which is allowed at any time. Both write the profile's label overrides
 * and feature flags immediately (organisation-wide, no Site required yet) and create any of its priority classes not
 * already present by name (also organisation-wide); {@code reset} never removes or deactivates an existing class, so
 * a class already in use by waiting or historical tickets is untouched. The starter catalogue and numbering defaults
 * are Site-scoped and a Site does not exist until a later wizard step, so they are carried as data for the wizard's
 * "services and numbering" step to offer, rather than created here (CFG-003 still applies: every value a profile
 * sets, once created, is editable through the ordinary admin screens).
 */
@Service
@Profile(Profiles.SERVING)
public class VerticalProfileService {

    static final String ACTIVE_PROFILE_KEY = "active_profile";
    static final String REPORT_KPI_DEFAULTS_KEY = "report_kpi_defaults";

    private final VerticalProfileCatalog catalog;
    private final SystemSettingRepository settings;
    private final LabelOverrideRepository labels;
    private final FeatureFlagRepository flags;
    private final PriorityService priority;
    private final AuditWriter audit;
    private final Clock clock;

    VerticalProfileService(
            VerticalProfileCatalog catalog,
            SystemSettingRepository settings,
            LabelOverrideRepository labels,
            FeatureFlagRepository flags,
            PriorityService priority,
            AuditWriter audit,
            Clock clock) {
        this.catalog = catalog;
        this.settings = settings;
        this.labels = labels;
        this.flags = flags;
        this.priority = priority;
        this.audit = audit;
        this.clock = clock;
    }

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    @PreAuthorize(PERMISSION)
    public List<VerticalProfileDefinition> list() {
        return catalog.all().values().stream().toList();
    }

    @PreAuthorize(PERMISSION)
    public VerticalProfileDefinition get(VerticalProfileId id) {
        return catalog.get(id);
    }

    @PreAuthorize(PERMISSION)
    public Optional<ActiveProfile> active() {
        return settings.get(ACTIVE_PROFILE_KEY).map(VerticalProfileService::toActiveProfile);
    }

    /** First-run only (CFG-002): refused once a profile is already active. Use {@link #reset} to change it later. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public ActiveProfile apply(VerticalProfileId id, UUID actorId) {
        if (active().isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "setup.refused.already_provisioned", new Object[0], Map.of("reason", "already_provisioned"));
        }
        return applyInternal(id, actorId, "profile.applied");
    }

    /** The explicit "reset to profile" action (CFG-002): allowed at any time, re-seeds labels, flags and priority classes. */
    @PreAuthorize(PERMISSION)
    @Transactional
    public ActiveProfile reset(VerticalProfileId id, UUID actorId) {
        return applyInternal(id, actorId, "profile.reset");
    }

    private ActiveProfile applyInternal(VerticalProfileId id, UUID actorId, String auditAction) {
        VerticalProfileDefinition definition = catalog.get(id);
        Instant now = clock.instant();

        labels.upsertAll(definition.labels(), actorId, now);
        flags.upsertAll(definition.featureFlags(), actorId, now);
        createMissingPriorityClasses(definition, actorId);

        Map<String, Object> reportDefaults = new LinkedHashMap<>();
        reportDefaults.put("report_defaults", definition.reportDefaults());
        reportDefaults.put("kpi_thresholds", definition.kpiThresholds());
        settings.set(REPORT_KPI_DEFAULTS_KEY, reportDefaults, actorId, now);

        Map<String, Object> activeProfileValue = new LinkedHashMap<>();
        activeProfileValue.put("id", id.wire());
        activeProfileValue.put("applied_at", now.toString());
        activeProfileValue.put("applied_by", actorId == null ? null : actorId.toString());
        settings.set(ACTIVE_PROFILE_KEY, activeProfileValue, actorId, now);

        audit.record(AuditEvent.of(auditAction, "vertical_profile", null).withAfter(Map.of("profile_id", id.wire())));

        return toActiveProfile(activeProfileValue);
    }

    /** Priority classes are organisation-wide (no Site dependency), so a profile can seed them immediately. Matched
     * by English name so re-applying (reset) never creates a duplicate, and the baseline default class ("Normal")
     * is always left alone. */
    private void createMissingPriorityClasses(VerticalProfileDefinition definition, UUID actorId) {
        Set<String> existing = priority.classes().stream()
                .map(PriorityClass::nameI18n)
                .map(names -> names.get("en"))
                .filter(java.util.Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (VerticalProfileDefinition.StarterPriorityClass starter : definition.priorityClasses()) {
            String en = starter.nameI18n().get("en");
            if (en != null && existing.contains(en.toLowerCase(Locale.ROOT))) continue;
            priority.create(new PriorityClassRequest(starter.nameI18n(), starter.headstartMinutes(), null, null));
        }
    }

    private static ActiveProfile toActiveProfile(Map<String, Object> value) {
        Object appliedAt = value.get("applied_at");
        Object appliedBy = value.get("applied_by");
        return new ActiveProfile(
                String.valueOf(value.get("id")), appliedAt == null ? null : Instant.parse(String.valueOf(appliedAt)), appliedBy == null ? null : String.valueOf(appliedBy));
    }
}
