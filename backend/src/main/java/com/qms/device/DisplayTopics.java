package com.qms.device;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.TopicSource;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.Authz;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Permission;
import com.qms.platform.security.Role;
import com.qms.platform.security.ScopeGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code zone:{zone_id}} topic (§21.2, ticket 28, FR-DSP-010, FR-DSP-011): a display board's whole live feed --
 * the same "now serving" and "next" state {@code GET /devices/{id}/display-state} returns, kept live. A display
 * paired to the zone may always watch its own zone's topic, the same as a device may always watch its own
 * {@code device:} topic; a staff caller needs {@code config:org_sites_zones} and scope over the zone's site, the
 * same reach fleet administration already has over the device itself.
 */
@Component
class DisplayTopics implements TopicSource {

    private final DeviceRepository devices;
    private final DisplayStateReads reads;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Authz authz;

    DisplayTopics(DeviceRepository devices, DisplayStateReads reads, CurrentUser currentUser, ScopeGuard scope, Authz authz) {
        this.devices = devices;
        this.reads = reads;
        this.currentUser = currentUser;
        this.scope = scope;
        this.authz = authz;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.ZONE);
    }

    @Override
    public void authorize(String topic) {
        UUID zoneId = zoneId(topic);
        UUID siteId = reads.siteIdOfZone(zoneId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        UUID subscriber = currentUser.require().userId();
        if (devices.findById(subscriber)
                .filter(device -> device.kind() == Role.DISPLAY && device.active() && zoneId.equals(device.zoneId()))
                .isPresent()) {
            return; // a display always may watch its own zone
        }
        authz.require(Permission.CONFIG_ORG_SITES_ZONES);
        scope.requireSite(siteId);
    }

    @Override
    public Map<String, Object> snapshot(String topic) {
        UUID zoneId = zoneId(topic);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serving", reads.serving(zoneId).stream().map(DisplayTopics::servingMap).toList());
        data.put("next", reads.next(zoneId).stream().map(DisplayTopics::nextMap).toList());
        return data;
    }

    private static Map<String, Object> servingMap(DisplayStateReads.ServingRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("counter_id", row.counterId().toString());
        map.put("counter_label", row.counterLabel());
        map.put("token_number", row.tokenNumber());
        map.put("state", row.state());
        map.put("service_id", row.serviceId() == null ? null : row.serviceId().toString());
        map.put("service_names", row.serviceNames());
        map.put("staff_name", row.staffName());
        return map;
    }

    private static Map<String, Object> nextMap(DisplayStateReads.NextGroup group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("service_id", group.serviceId().toString());
        map.put("service_names", group.serviceNames());
        map.put("tokens", group.tokens().stream()
                .map(t -> (Map<String, Object>) new LinkedHashMap<String, Object>(Map.of("token_number", t.tokenNumber(), "position", t.position())))
                .toList());
        return map;
    }

    private static UUID zoneId(String topic) {
        String raw = topic.substring(Topics.ZONE.length());
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "topic", "code", "invalid"))));
        }
    }
}
