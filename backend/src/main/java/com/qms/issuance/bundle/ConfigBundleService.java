package com.qms.issuance.bundle;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.configuration.catalogue.CatalogueService;
import com.qms.configuration.catalogue.ServiceEntry;
import com.qms.configuration.catalogue.ServiceGroup;
import com.qms.configuration.priority.PriorityClass;
import com.qms.configuration.priority.PriorityClassRequest;
import com.qms.configuration.priority.PriorityDefaultRequest;
import com.qms.configuration.priority.PriorityDefaults;
import com.qms.configuration.priority.PriorityService;
import com.qms.configuration.priority.RoutingStrategyRequest;
import com.qms.configuration.priority.RoutingStrategyView;
import com.qms.configuration.site.HierarchyService;
import com.qms.configuration.site.Site;
import com.qms.issuance.IssuanceRulesService;
import com.qms.issuance.IssuanceRulesViews.Hours;
import com.qms.issuance.IssuanceRulesViews.WeekDay;
import com.qms.issuance.NumberingRuleRequest;
import com.qms.issuance.NumberingRuleView;
import com.qms.issuance.NumberingService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports and imports the whole of what FR-CFG-040 versions as one signed JSON bundle (CFG-004, ticket 55): every
 * Priority class and its channel/Service defaults, every Service group's own routing strategy, every scope's own
 * numbering rule, and every scope's own business-hours week. The org hierarchy and service catalogue those scopes
 * are keyed by (Site, Service group, Service) are not themselves in the bundle: SRS §3.7 already gives a client three
 * environments "of the same version", i.e. sharing that structure, so a clone applies these configuration values onto
 * a target that already has matching Site/Service group/Service ids — exactly the staging/training use this ticket
 * names. A scope the target does not have fails that one reference with {@code not_found} and aborts the whole import
 * (it runs in one transaction): nothing is applied part-way.
 *
 * <p>Only an organisation-wide caller (no {@code site_ids} claim) may export or import, since a scoped Site or Team
 * admin editing one area already goes through that area's own endpoint; the permission checks of {@link
 * PriorityService}, {@link NumberingService} and {@link IssuanceRulesService} apply again underneath every call here
 * (API-016), so a caller without every one of {@code config:priority_routing}, {@code config:service_catalogue} and
 * {@code config:org_sites_zones} cannot complete either operation.
 */
@Service
@Profile(Profiles.SERVING)
public class ConfigBundleService {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    private static final String HMAC = "HmacSHA256";
    /** The wire values {@code com.qms.issuance.NumberingRule} uses; not visible from here (package-private). */
    private static final String SCOPE_SERVICE = "service";
    private static final String SCOPE_SERVICE_GROUP = "service_group";
    private static final String SCOPE_SITE = "site";

    private final HierarchyService hierarchy;
    private final CatalogueService catalogue;
    private final PriorityService priority;
    private final NumberingService numbering;
    private final IssuanceRulesService issuanceRules;
    private final ConfigBundleProperties properties;
    private final CurrentUser currentUser;
    private final AuditWriter audit;
    private final Clock clock;
    private final JsonMapper mapper;

    ConfigBundleService(
            HierarchyService hierarchy,
            CatalogueService catalogue,
            PriorityService priority,
            NumberingService numbering,
            IssuanceRulesService issuanceRules,
            ConfigBundleProperties properties,
            CurrentUser currentUser,
            AuditWriter audit,
            Clock clock,
            JsonMapper mapper) {
        this.hierarchy = hierarchy;
        this.catalogue = catalogue;
        this.priority = priority;
        this.numbering = numbering;
        this.issuanceRules = issuanceRules;
        this.properties = properties;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ConfigBundleResponse export() {
        requireOrganisationWide();
        String secret = requireSecret();
        Map<String, Object> payload = buildPayload();
        String json = mapper.writeValueAsString(payload);
        String signature = sign(json, secret);
        audit.record(AuditEvent.of("config_bundle.exported", "config_bundle", null));
        return new ConfigBundleResponse(clock.instant(), json, signature);
    }

    @PreAuthorize(PERMISSION)
    @Transactional
    public ConfigBundleImportResult apply(ConfigBundleImportRequest request) {
        requireOrganisationWide();
        String secret = requireSecret();
        if (request == null || request.payloadJson() == null || request.payloadJson().isBlank() || request.signature() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "payload_json", "code", "NotBlank"))));
        }
        if (!constantTimeEquals(sign(request.payloadJson(), secret), request.signature())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("reason", "invalid_signature"));
        }
        Map<String, Object> payload = readPayload(request.payloadJson());
        Map<String, Integer> counts = applyPayload(payload);
        audit.record(AuditEvent.of("config_bundle.imported", "config_bundle", null).withAfter(Map.of("applied_counts", counts)));
        return new ConfigBundleImportResult(counts);
    }

    // ---- export -------------------------------------------------------------------------------------------------

    private Map<String, Object> buildPayload() {
        List<Site> sites = hierarchy.sites();

        List<Map<String, Object>> priorityClasses = new ArrayList<>();
        for (PriorityClass c : priority.classes()) priorityClasses.add(classPayload(c));

        PriorityDefaults defaults = priority.defaults();
        List<Map<String, Object>> channelDefaults = new ArrayList<>();
        for (PriorityDefaults.Channel c : defaults.channels()) {
            if (c.priorityClassId() != null) channelDefaults.add(Map.of("channel", c.channel(), "priority_class_id", c.priorityClassId().toString()));
        }
        List<Map<String, Object>> serviceDefaults = new ArrayList<>();
        for (PriorityDefaults.Service s : defaults.services()) {
            serviceDefaults.add(Map.of("service_id", s.serviceId().toString(), "priority_class_id", s.priorityClassId().toString()));
        }

        List<Map<String, Object>> routingStrategies = new ArrayList<>();
        List<Map<String, Object>> numberingRules = new ArrayList<>();
        List<Map<String, Object>> businessHours = new ArrayList<>();

        for (Site site : sites) {
            addHoursIfSet(businessHours, SCOPE_SITE, site.id(), issuanceRules.siteHours(site.id()));
            for (NumberingRuleView rule : numbering.rules(site.id())) numberingRules.add(numberingPayload(rule));

            for (ServiceGroup group : catalogue.groups(site.id())) {
                RoutingStrategyView strategy = priority.strategy(group.id());
                if (!strategy.isDefault()) routingStrategies.add(Map.of("service_group_id", group.id().toString(), "strategy", strategy.strategy()));

                for (ServiceEntry svc : catalogue.services(group.id())) {
                    addHoursIfSet(businessHours, SCOPE_SERVICE, svc.id(), issuanceRules.serviceHours(svc.id()));
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bundle_version", 1);
        payload.put("priority_classes", priorityClasses);
        payload.put("priority_defaults", Map.of("channels", channelDefaults, "services", serviceDefaults));
        payload.put("routing_strategies", routingStrategies);
        payload.put("numbering_rules", numberingRules);
        payload.put("business_hours", businessHours);
        return payload;
    }

    private static Map<String, Object> classPayload(PriorityClass c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", c.id().toString());
        values.put("name_i18n", c.nameI18n());
        values.put("headstart_minutes", c.headstartMinutes());
        values.put("max_wait_minutes", c.maxWaitMinutes());
        values.put("token_prefix_override", c.tokenPrefixOverride());
        values.put("is_default", c.isDefault());
        values.put("active", c.active());
        return values;
    }

    private static Map<String, Object> numberingPayload(NumberingRuleView rule) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scope_type", rule.scopeType());
        values.put("scope_id", rule.scopeId().toString());
        values.put("prefix_source", rule.prefixSource());
        values.put("fixed_prefix", rule.fixedPrefix());
        values.put("sequence_start", rule.sequenceStart());
        values.put("padding", rule.padding());
        values.put("reset_boundary", rule.resetBoundary());
        values.put("reset_time", rule.resetTime());
        values.put("separator", rule.separator());
        return values;
    }

    private static void addHoursIfSet(List<Map<String, Object>> into, String scopeType, UUID scopeId, Hours hours) {
        if (hours.days().isEmpty()) return; // follows its parent scope; nothing of its own to carry
        into.add(Map.of("scope_type", scopeType, "scope_id", scopeId.toString(), "days", hours.days()));
    }

    // ---- import -------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Integer> applyPayload(Map<String, Object> payload) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("priority_classes", applyPriorityClasses((List<Map<String, Object>>) payload.getOrDefault("priority_classes", List.of())));

        Map<String, Object> defaults = (Map<String, Object>) payload.getOrDefault("priority_defaults", Map.of());
        counts.put("priority_defaults", applyPriorityDefaults(
                (List<Map<String, Object>>) defaults.getOrDefault("channels", List.of()),
                (List<Map<String, Object>>) defaults.getOrDefault("services", List.of())));

        counts.put("routing_strategies", applyRoutingStrategies((List<Map<String, Object>>) payload.getOrDefault("routing_strategies", List.of())));
        counts.put("numbering_rules", applyNumberingRules((List<Map<String, Object>>) payload.getOrDefault("numbering_rules", List.of())));
        counts.put("business_hours", applyBusinessHours((List<Map<String, Object>>) payload.getOrDefault("business_hours", List.of())));
        return counts;
    }

    @SuppressWarnings("unchecked")
    private int applyPriorityClasses(List<Map<String, Object>> entries) {
        UUID targetDefaultId = priority.classes().stream().filter(PriorityClass::isDefault).map(PriorityClass::id).findFirst().orElse(null);
        int applied = 0;
        for (Map<String, Object> entry : entries) {
            PriorityClassRequest request = new PriorityClassRequest(
                    (Map<String, String>) entry.get("name_i18n"),
                    (Integer) entry.get("headstart_minutes"),
                    (Integer) entry.get("max_wait_minutes"),
                    (String) entry.get("token_prefix_override"));
            boolean isDefault = Boolean.TRUE.equals(entry.get("is_default"));
            if (isDefault) {
                if (targetDefaultId != null) priority.replace(targetDefaultId, request);
            } else {
                priority.restore(UUID.fromString((String) entry.get("id")), request, false);
            }
            applied++;
        }
        return applied;
    }

    private int applyPriorityDefaults(List<Map<String, Object>> channels, List<Map<String, Object>> services) {
        int applied = 0;
        for (Map<String, Object> c : channels) {
            priority.setChannelDefault((String) c.get("channel"), new PriorityDefaultRequest(UUID.fromString((String) c.get("priority_class_id"))));
            applied++;
        }
        for (Map<String, Object> s : services) {
            priority.setServiceDefault(UUID.fromString((String) s.get("service_id")), new PriorityDefaultRequest(UUID.fromString((String) s.get("priority_class_id"))));
            applied++;
        }
        return applied;
    }

    private int applyRoutingStrategies(List<Map<String, Object>> entries) {
        int applied = 0;
        for (Map<String, Object> entry : entries) {
            priority.setStrategy(UUID.fromString((String) entry.get("service_group_id")), new RoutingStrategyRequest((String) entry.get("strategy")));
            applied++;
        }
        return applied;
    }

    private int applyNumberingRules(List<Map<String, Object>> entries) {
        int applied = 0;
        for (Map<String, Object> entry : entries) {
            NumberingRuleRequest request = new NumberingRuleRequest(
                    (String) entry.get("prefix_source"),
                    (String) entry.get("fixed_prefix"),
                    entry.get("sequence_start") == null ? null : ((Number) entry.get("sequence_start")).longValue(),
                    entry.get("padding") == null ? null : ((Number) entry.get("padding")).intValue(),
                    (String) entry.get("reset_boundary"),
                    (String) entry.get("reset_time"),
                    (String) entry.get("separator"));
            numbering.setRule((String) entry.get("scope_type"), UUID.fromString((String) entry.get("scope_id")), request);
            applied++;
        }
        return applied;
    }

    @SuppressWarnings("unchecked")
    private int applyBusinessHours(List<Map<String, Object>> entries) {
        int applied = 0;
        for (Map<String, Object> entry : entries) {
            List<Map<String, Object>> days = (List<Map<String, Object>>) entry.get("days");
            List<WeekDay> weekDays = days.stream()
                    .map(d -> new WeekDay(((Number) d.get("weekday")).intValue(), (String) d.get("open"), (String) d.get("close")))
                    .toList();
            String scopeType = (String) entry.get("scope_type");
            UUID scopeId = UUID.fromString((String) entry.get("scope_id"));
            if (SCOPE_SITE.equals(scopeType)) issuanceRules.setSiteHours(scopeId, new Hours(weekDays));
            else issuanceRules.setServiceHours(scopeId, new Hours(weekDays));
            applied++;
        }
        return applied;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void requireOrganisationWide() {
        if (!currentUser.require().siteIds().isEmpty()) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private String requireSecret() {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) throw new ApiException(ErrorCode.UNAVAILABLE, Map.of("reason", "bundle_secret_not_configured"));
        return secret;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        return new LinkedHashMap<>(mapper.readValue(json, LinkedHashMap.class));
    }

    private static String sign(String json, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String expected, String given) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
