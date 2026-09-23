package com.qms.configuration.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.platform.Profiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds a Site's starter service catalogue from a vertical profile's flat {@code starter_services} list (ticket 67,
 * ticket 56's deferred item). An explicit, idempotent, per-Site action — never run by {@code VerticalProfileService}
 * itself, since a Site does not exist yet when a profile is first applied (§26.2's own step order).
 *
 * <p>Every group and service is created through {@link CatalogueService#createGroup}/{@link
 * CatalogueService#createService}, so every {@link CatalogueRules} validation and their own audit entries apply
 * exactly as they do to a hand-built group or service. This class adds only what a straight loop over those two
 * calls would not have on its own: idempotency (an existing group or service, matched by its English name, is
 * skipped rather than duplicated or modified — running it twice creates nothing new) and a token-prefix clash check
 * (a starter service whose prefix is already used by another active service anywhere at the Site is skipped and
 * reported, never silently renamed).
 *
 * <p>Public because {@link CreateServiceGroupRequest}/{@link CreateServiceRequest} are package-private: this is the
 * one seam a caller outside {@code com.qms.configuration.catalogue} (the setup wizard's own orchestration) may use
 * to reach them.
 */
@Component
@Profile(Profiles.SERVING)
public class CatalogueSeeding {

    /** A token prefix for the one seeded group itself; unused by numbering (the seeded rule's own prefix source is
     * {@code service}, so each service keeps its own letter), so any valid value works and no uniqueness is owed. */
    private static final String GROUP_TOKEN_PREFIX = "SVC";
    private static final int DEFAULT_EXPECTED_MINUTES = 10;
    private static final int DEFAULT_SLA_WAIT_MINUTES = 30;

    private final CatalogueService catalogueService;
    private final CatalogueRepository repository;

    CatalogueSeeding(CatalogueService catalogueService, CatalogueRepository repository) {
        this.catalogueService = catalogueService;
        this.repository = repository;
    }

    /** The one group a profile seeds, named from its own {@code entity.service_group} label (design decision, ticket 67). */
    public record GroupDef(Map<String, String> nameI18n) {}

    /** One profile starter service: a name and the token prefix it keeps once created. */
    public record ServiceDef(Map<String, String> nameI18n, String tokenPrefix) {}

    /** {@code kind} is {@code service_group} or {@code service}; {@code name} is the English name seeded or matched. */
    public record SeededItem(String kind, String name) {}

    /** As {@link SeededItem}, plus why it was left alone: {@code already_exists} (matched by name) or {@code prefix_in_use}. */
    public record SkippedItem(String kind, String name, String reason) {}

    public record SeedResult(@JsonProperty("service_group_id") UUID groupId, List<SeededItem> created, List<SkippedItem> skipped) {}

    public SeedResult seedStarter(UUID siteId, GroupDef groupDef, List<ServiceDef> services) {
        List<SeededItem> created = new ArrayList<>();
        List<SkippedItem> skipped = new ArrayList<>();

        List<ServiceGroup> siteGroups = new ArrayList<>(repository.groupsOfSite(siteId));
        String groupName = englishName(groupDef.nameI18n());
        ServiceGroup group = siteGroups.stream()
                .filter(g -> groupName.equalsIgnoreCase(englishName(g.nameI18n())))
                .findFirst()
                .orElse(null);
        if (group == null) {
            group = catalogueService.createGroup(
                    siteId, new CreateServiceGroupRequest(groupDef.nameI18n(), GROUP_TOKEN_PREFIX, null, null, null, null, null));
            created.add(new SeededItem("service_group", groupName));
            siteGroups.add(group);
        } else {
            skipped.add(new SkippedItem("service_group", groupName, "already_exists"));
        }

        Set<String> usedPrefixes = new HashSet<>();
        for (ServiceGroup g : siteGroups) {
            for (ServiceEntry existing : repository.servicesOfGroup(g.id())) {
                if (existing.active()) usedPrefixes.add(existing.tokenPrefix().toUpperCase(Locale.ROOT));
            }
        }

        List<ServiceEntry> groupServices = repository.servicesOfGroup(group.id());
        for (ServiceDef def : services) {
            String serviceName = englishName(def.nameI18n());
            boolean exists = groupServices.stream().anyMatch(s -> serviceName.equalsIgnoreCase(englishName(s.nameI18n())));
            if (exists) {
                skipped.add(new SkippedItem("service", serviceName, "already_exists"));
                continue;
            }
            if (usedPrefixes.contains(def.tokenPrefix().toUpperCase(Locale.ROOT))) {
                skipped.add(new SkippedItem("service", serviceName, "prefix_in_use"));
                continue;
            }
            ServiceEntry entry = catalogueService.createService(
                    group.id(),
                    new CreateServiceRequest(
                            def.nameI18n(), def.tokenPrefix(), DEFAULT_EXPECTED_MINUTES, DEFAULT_SLA_WAIT_MINUTES,
                            null, null, null, null, null, null, null, null));
            created.add(new SeededItem("service", serviceName));
            usedPrefixes.add(entry.tokenPrefix().toUpperCase(Locale.ROOT));
        }

        return new SeedResult(group.id(), List.copyOf(created), List.copyOf(skipped));
    }

    private static String englishName(Map<String, String> nameI18n) {
        return nameI18n == null ? null : nameI18n.get("en");
    }
}
