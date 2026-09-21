package com.qms.platform.connectivity;

import com.qms.platform.Profiles;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Whether this Site currently has an internet uplink (ticket 44, FR-QUE-202, FR-MOB-041, ADR-0001): the one fact
 * {@code com.qms.issuance.IssuanceGate} (remote join) and {@code com.qms.notification.NotificationConfigController}
 * (Web Push) consult before letting a visitor start something that needs it, so both degrade the same way at the
 * same moment — shown as unavailable, not by silently failing whatever they tried. Everything else (walk-in
 * issuance on the LAN, an already-remote ticket's own in-person check-in) never asks this and so is entirely
 * unaffected (ADR-0001, FR-MOB-041).
 *
 * <p>Starts — and stays, with no {@link InternetConnectivityProperties#probeTargets()} configured — reachable: an
 * installation that has not set this up behaves exactly as it did before this ticket (fail open). Once probing is
 * on, it takes {@link InternetConnectivityProperties#failureThreshold()} consecutive sweeps with every target
 * unreachable to flip the Site offline (one blip is not an outage), but a single sweep with any target reachable
 * restores it at once. Every transition writes one audit entry via {@link ConnectivityAuditSink}.
 *
 * <p>{@code @Profile(Profiles.SERVING)}: its only implementation of {@link InternetReachabilityChecker} ({@link
 * HttpInternetReachabilityChecker}) is itself SERVING-only (the same reason every consumer of this class already
 * is), so this must not be instantiated under {@code migrate}/{@code rotate-keys}, which start no such bean.
 */
@Component
@Profile(Profiles.SERVING)
public class InternetConnectivityMonitor {

    private final InternetReachabilityChecker checker;
    private final InternetConnectivityProperties properties;
    private final ConnectivityAuditSink auditSink;

    private final AtomicBoolean reachable = new AtomicBoolean(true);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    InternetConnectivityMonitor(InternetReachabilityChecker checker, InternetConnectivityProperties properties, ConnectivityAuditSink auditSink) {
        this.checker = checker;
        this.properties = properties;
        this.auditSink = auditSink;
    }

    public boolean reachable() {
        return reachable.get();
    }

    /** Ticked by {@link InternetConnectivityScheduler} on its own cron, or directly by a test. */
    public void check() {
        List<URI> targets = properties.probeTargets();
        if (targets.isEmpty()) return; // nothing configured to probe: stay at the fail-open default
        boolean anyReachable = targets.stream().anyMatch(checker::reachable);
        if (anyReachable) {
            consecutiveFailures.set(0);
            if (reachable.compareAndSet(false, true)) auditSink.recordTransition(true);
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= properties.failureThreshold() && reachable.compareAndSet(true, false)) {
            auditSink.recordTransition(false);
        }
    }
}
