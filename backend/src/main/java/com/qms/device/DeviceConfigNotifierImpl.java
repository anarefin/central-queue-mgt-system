package com.qms.device;

import com.qms.platform.Profiles;
import com.qms.platform.devices.DeviceConfigNotifier;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Pushes {@code config.changed} to every affected live device, on the same {@code device:{id}} topic
 * {@link DeviceService#command} and {@link DeviceService#updateDisplayConfig} already use (FR-OPS-042, SRS §21.4).
 */
@Component
@Profile(Profiles.SERVING)
class DeviceConfigNotifierImpl implements DeviceConfigNotifier {

    private final DeviceRepository devices;
    private final RealtimePublisher realtime;
    private final Clock clock;

    DeviceConfigNotifierImpl(DeviceRepository devices, RealtimePublisher realtime, Clock clock) {
        this.devices = devices;
        this.realtime = realtime;
        this.clock = clock;
    }

    @Override
    public void notifySite(UUID siteId) {
        for (UUID id : devices.activeIdsOfSite(siteId)) push(id);
    }

    @Override
    public void notifyEverySite() {
        for (UUID id : devices.activeIds()) push(id);
    }

    private void push(UUID deviceId) {
        realtime.publish(Topics.device(deviceId), "config.changed", clock.instant(), Map.of());
    }
}
