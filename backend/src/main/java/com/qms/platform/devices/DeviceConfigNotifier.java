package com.qms.platform.devices;

import java.util.UUID;

/**
 * The one seam a bounded context tells the device fleet its configuration changed through (SRS §21.4's
 * {@code config.changed}, FR-OPS-042, FR-CFG-040): implemented once by {@code com.qms.device.DeviceConfigNotifierImpl},
 * the same separation {@code RealtimePublisher} and {@code NotificationTrigger} already give realtime delivery and
 * notifications, so a caller such as {@code com.qms.configuration.priority.PriorityService} never learns which
 * devices exist or how the push reaches them. Kept optional at the injection site: a context that runs without the
 * device module loaded (a slice test) simply sends nothing.
 */
public interface DeviceConfigNotifier {

    /** A Site-scoped change (a routing strategy, a numbering rule, a business-hours week): every active device of that Site. */
    void notifySite(UUID siteId);

    /** An organisation-wide change (a Priority class): every active device across every Site. */
    void notifyEverySite();
}
