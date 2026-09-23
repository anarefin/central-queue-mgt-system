package com.qms.issuance.setup;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.catalogue.CatalogueSeeding;
import com.qms.configuration.site.HierarchyService;
import com.qms.configuration.site.Site;
import com.qms.issuance.NumberingRuleRequest;
import com.qms.issuance.NumberingService;
import com.qms.issuance.NumberingSpec;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a Site's starter catalogue and numbering from the installation's active vertical profile (ticket 67, ticket
 * 56's own deferred item — see its traceability notes). An explicit, idempotent, per-Site admin action: never run
 * automatically by {@link VerticalProfileService#apply}/{@code reset}, since a Site does not exist yet when a
 * profile is first applied (§26.2's own step order picks the profile before "create org and sites").
 *
 * <p>Needs both {@code config:org_sites_zones} (the Site itself) and {@code config:service_catalogue} (what gets
 * created under it) — the two permissions the catalogue/numbering admin screens this replaces would otherwise need
 * separately. Every change still goes through {@link CatalogueSeeding}, which reuses {@code CatalogueService}'s own
 * validation and audit trail; this class adds the numbering rule and its own {@code profile.catalogue_seeded} audit
 * entry on top.
 */
@Service
@Profile(Profiles.SERVING)
public class CatalogueSeedingService {

    private static final String PERMISSION =
            "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)"
                    + " and hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final HierarchyService hierarchy;
    private final VerticalProfileService profiles;
    private final CatalogueSeeding seeding;
    private final NumberingService numbering;
    private final AuditWriter audit;

    CatalogueSeedingService(
            HierarchyService hierarchy,
            VerticalProfileService profiles,
            CatalogueSeeding seeding,
            NumberingService numbering,
            AuditWriter audit) {
        this.hierarchy = hierarchy;
        this.profiles = profiles;
        this.seeding = seeding;
        this.numbering = numbering;
        this.audit = audit;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public CatalogueSeeding.SeedResult seedStarter(UUID siteId) {
        // HierarchyService.site() both confirms the Site exists (not_found) and that it is within the caller's own
        // scope (forbidden otherwise, FR-CFG-106) before anything else runs.
        Site site = hierarchy.site(siteId);
        if (!site.active()) throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "parent_inactive"));

        ActiveProfile active = profiles.active().orElseThrow(CatalogueSeedingService::profileNotApplied);
        VerticalProfileId profileId = VerticalProfileId.tryFromWire(active.id()).orElseThrow(CatalogueSeedingService::profileNotApplied);
        VerticalProfileDefinition definition = profiles.get(profileId);

        CatalogueSeeding.GroupDef groupDef = new CatalogueSeeding.GroupDef(definition.labels().get("entity.service_group"));
        List<CatalogueSeeding.ServiceDef> serviceDefs = definition.starterServices().stream()
                .map(s -> new CatalogueSeeding.ServiceDef(s.nameI18n(), s.tokenPrefix()))
                .toList();

        CatalogueSeeding.SeedResult result = seeding.seedStarter(siteId, groupDef, serviceDefs);

        // The numbering rule is one service_group-scoped rule, prefix source "service" (each seeded service keeps
        // its own letter), from the profile's own numbering_defaults -- left exactly as it is if the group already
        // carries a rule (ticket 67), including on a re-run of this same action.
        if (!numbering.hasRule(NumberingSpec.SERVICE_GROUP, result.groupId())) {
            VerticalProfileDefinition.NumberingDefaults defaults = definition.numberingDefaults();
            numbering.setRule(
                    NumberingSpec.SERVICE_GROUP,
                    result.groupId(),
                    new NumberingRuleRequest(
                            NumberingSpec.SERVICE, null, (long) defaults.sequenceStart(), defaults.padding(), defaults.resetBoundary(), null, null));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("profile_id", active.id());
        after.put("site_id", siteId.toString());
        after.put("created_count", result.created().size());
        after.put("skipped_count", result.skipped().size());
        audit.record(AuditEvent.of("profile.catalogue_seeded", "service_group", result.groupId()).withAfter(after));

        return result;
    }

    private static ApiException profileNotApplied() {
        return new ApiException(ErrorCode.CONFLICT, "setup.refused.profileNotApplied", new Object[0], Map.of("reason", "profile_not_applied"));
    }
}
