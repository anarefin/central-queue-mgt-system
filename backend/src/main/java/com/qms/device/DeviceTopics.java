package com.qms.device;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.TopicSource;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.Authz;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.Permission;
import com.qms.platform.security.ScopeGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code device:{device_id}} topic (§21.2): config-change, reload and revoke commands for one device
 * (FR-OPS-042). The device itself may always subscribe to its own topic; a staff caller may watch or push to a
 * device it administers, i.e. one it may already reach through {@code config:org_sites_zones} (FR-QUE-080).
 */
@Component
class DeviceTopics implements TopicSource {

    private final DeviceRepository devices;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Authz authz;

    DeviceTopics(DeviceRepository devices, CurrentUser currentUser, ScopeGuard scope, Authz authz) {
        this.devices = devices;
        this.currentUser = currentUser;
        this.scope = scope;
        this.authz = authz;
    }

    @Override
    public boolean handles(String topic) {
        return topic.startsWith(Topics.DEVICE);
    }

    @Override
    public void authorize(String topic) {
        Device device = device(topic);
        UUID subscriber = currentUser.require().userId();
        if (subscriber.equals(device.id())) return; // a device always may watch its own topic
        authz.require(Permission.CONFIG_ORG_SITES_ZONES);
        scope.requireSite(device.siteId());
    }

    @Override
    public Map<String, Object> snapshot(String topic) {
        Device device = device(topic);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", device.active());
        data.put("last_heartbeat_at", device.lastHeartbeatAt() == null ? null : device.lastHeartbeatAt().toString());
        return data;
    }

    private Device device(String topic) {
        String raw = topic.substring(Topics.DEVICE.length());
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        return devices.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
