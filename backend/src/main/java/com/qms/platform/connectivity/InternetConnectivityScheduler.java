package com.qms.platform.connectivity;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Ticks {@link InternetConnectivityMonitor#check()} on its own cron (ticket 44); {@code -} (the default) disables it
 * so a test can drive the check itself directly, the same {@code @Scheduled} shape {@code RemoteArrivalScheduler}
 * already uses.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile(Profiles.SERVING)
class InternetConnectivityScheduler {

    private final InternetConnectivityMonitor monitor;

    InternetConnectivityScheduler(InternetConnectivityMonitor monitor) {
        this.monitor = monitor;
    }

    @Scheduled(cron = "${qms.connectivity.check-cron:-}", zone = "UTC")
    void tick() {
        monitor.check();
    }
}
