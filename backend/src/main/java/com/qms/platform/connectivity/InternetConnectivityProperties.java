package com.qms.platform.connectivity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Internet-reachability monitoring (ticket 44, FR-QUE-202, FR-MOB-041, ADR-0001): in Phase 1 the core runs on the
 * Site's own LAN (§6.3), so an operator names the external targets that stand for "this Site still has an internet
 * uplink" — typically its own public origin (what a remote visitor's phone reaches it through) and a Web Push
 * provider. Any one of them answering counts as reachable; none configured leaves the Site assumed reachable (fail
 * open), so an installation that never sets this up behaves exactly as it did before this ticket.
 *
 * @param probeTargets external URLs {@link InternetConnectivityMonitor} checks; empty (the default) disables checking
 * @param checkCron when {@link InternetConnectivityScheduler} probes them; {@code -} (the default) disables it so a
 *     test can drive the check itself, the same convention {@code qms.queue.remote-arrival-check-cron} uses
 * @param failureThreshold how many consecutive sweeps with every target unreachable before the Site is declared
 *     offline; one sweep with any target reachable restores it at once
 * @param timeout how long one probe request may take before it counts as unreachable
 */
@ConfigurationProperties("qms.connectivity")
public record InternetConnectivityProperties(
        @DefaultValue List<URI> probeTargets,
        @DefaultValue("-") String checkCron,
        @DefaultValue("3") int failureThreshold,
        @DefaultValue("3s") Duration timeout) {

    public InternetConnectivityProperties {
        if (failureThreshold < 1) throw new IllegalArgumentException("qms.connectivity.failure-threshold must be at least 1");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("qms.connectivity.timeout must be positive");
    }
}
