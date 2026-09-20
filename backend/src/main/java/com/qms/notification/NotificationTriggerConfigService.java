package com.qms.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qms.notification.NotificationTriggerConfigRepository.SettingRow;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Every trigger's enabled state and channel order, resolved per Site and per Service (FR-NTF-010): a Service-level
 * row overrides its Site's own row, which overrides the trigger catalogue's own default (FR-NTF-001). Reuses
 * {@code config:service_catalogue} the same way {@link NotificationTemplateService} does.
 */
@Service
public class NotificationTriggerConfigService {

    public record EffectiveSetting(
            @JsonProperty("trigger_key") String triggerKey,
            boolean enabled,
            @JsonProperty("channel_order") List<String> channelOrder,
            @JsonProperty("site_overridden") boolean siteOverridden,
            @JsonProperty("service_overridden") boolean serviceOverridden) {}

    private final NotificationTriggerConfigRepository repository;
    private final ScopeGuard scope;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    NotificationTriggerConfigService(NotificationTriggerConfigRepository repository, ScopeGuard scope, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.scope = scope;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What {@link NotificationDispatcher} resolves for one firing: no permission check, this is the runtime path, not admin. */
    EffectiveSetting effective(NotificationTriggerKey trigger, UUID siteId, UUID serviceId, String originChannel) {
        if (serviceId != null) {
            var service = repository.serviceSetting(siteId, serviceId, trigger.key());
            if (service.isPresent()) return resolved(trigger, service.get(), true, false);
        }
        var site = repository.siteSetting(siteId, trigger.key());
        if (site.isPresent()) return resolved(trigger, site.get(), false, true);
        return new EffectiveSetting(trigger.key(), trigger.defaultEnabled(originChannel), trigger.defaultChannelOrder(), false, false);
    }

    private EffectiveSetting resolved(NotificationTriggerKey trigger, SettingRow row, boolean serviceOverridden, boolean siteOverridden) {
        List<String> order = row.channelOrder() != null ? row.channelOrder() : trigger.defaultChannelOrder();
        return new EffectiveSetting(trigger.key(), row.enabled(), order, siteOverridden, serviceOverridden);
    }

    /** The admin listing for a Site: every catalogue trigger with its effective, site-wide setting (FR-NTF-010). */
    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public List<EffectiveSetting> listForSite(UUID siteId) {
        scope.requireSite(siteId);
        Map<String, SettingRow> overrides = new LinkedHashMap<>();
        for (SettingRow row : repository.forSite(siteId)) overrides.put(row.triggerKey(), row);
        return listAll(overrides, siteId, null);
    }

    /** The admin listing for one Service under a Site: every catalogue trigger with its effective setting there. */
    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public List<EffectiveSetting> listForService(UUID siteId, UUID serviceId) {
        scope.requireSite(siteId);
        requireServiceInSite(serviceId, siteId);
        Map<String, SettingRow> overrides = new LinkedHashMap<>();
        for (SettingRow row : repository.forService(siteId, serviceId)) overrides.put(row.triggerKey(), row);
        return listAll(overrides, siteId, serviceId);
    }

    private List<EffectiveSetting> listAll(Map<String, SettingRow> overrides, UUID siteId, UUID serviceId) {
        List<EffectiveSetting> result = new java.util.ArrayList<>();
        for (NotificationTriggerKey trigger : NotificationTriggerKey.values()) {
            SettingRow row = overrides.get(trigger.key());
            if (row != null) {
                result.add(resolved(trigger, row, serviceId != null, serviceId == null));
            } else {
                result.add(new EffectiveSetting(trigger.key(), trigger.defaultEnabled(null), trigger.defaultChannelOrder(), false, false));
            }
        }
        return result;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public EffectiveSetting setForSite(UUID siteId, String triggerKey, boolean enabled, List<String> channelOrder, UUID actorId) {
        scope.requireSite(siteId);
        NotificationTriggerKey trigger = NotificationTemplateService.requireTrigger(triggerKey);
        validateChannelOrder(channelOrder);
        repository.upsertSite(siteId, trigger.key(), enabled, channelOrder, actorId, clock.instant());
        return resolved(trigger, repository.siteSetting(siteId, trigger.key()).orElseThrow(), false, true);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)")
    public EffectiveSetting setForService(UUID siteId, UUID serviceId, String triggerKey, boolean enabled, List<String> channelOrder, UUID actorId) {
        scope.requireSite(siteId);
        requireServiceInSite(serviceId, siteId);
        NotificationTriggerKey trigger = NotificationTemplateService.requireTrigger(triggerKey);
        validateChannelOrder(channelOrder);
        repository.upsertService(siteId, serviceId, trigger.key(), enabled, channelOrder, actorId, clock.instant());
        return resolved(trigger, repository.serviceSetting(siteId, serviceId, trigger.key()).orElseThrow(), true, false);
    }

    private void validateChannelOrder(List<String> channelOrder) {
        if (channelOrder == null) return;
        for (String channel : channelOrder) {
            if (!NotificationTemplateService.ALL_CHANNELS.contains(channel)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "channel_order", "code", "unknown_channel"))));
            }
        }
    }

    private void requireServiceInSite(UUID serviceId, UUID siteId) {
        UUID actualSite = jdbc.query("SELECT site_id FROM service_group g JOIN service s ON s.service_group_id = g.id WHERE s.id = ?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, serviceId);
        if (actualSite == null || !actualSite.equals(siteId)) throw new ApiException(ErrorCode.NOT_FOUND);
    }
}
