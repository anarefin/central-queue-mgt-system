package com.qms.audit;

import com.qms.platform.connectivity.ConnectivityAuditSink;
import com.qms.platform.connectivity.InternetConnectivityMonitor;
import org.springframework.stereotype.Component;

/**
 * Writes the audit entry for one of {@link InternetConnectivityMonitor}'s own reachability transitions (ticket 44,
 * SRS §27.5 "specified events and audit entries"): {@code connectivity.internet_lost} when the Site's internet
 * uplink is declared down, {@code connectivity.internet_restored} when it is declared back up. No entity id — this
 * is a Site-wide condition, not one row's own history, the same shape {@code JourneyService}'s own
 * {@code journey_settings.changed} already uses for a setting rather than a record.
 */
@Component
class ConnectivityAuditWriter implements ConnectivityAuditSink {

    private final AuditWriter audit;

    ConnectivityAuditWriter(AuditWriter audit) {
        this.audit = audit;
    }

    @Override
    public void recordTransition(boolean reachable) {
        String action = reachable ? "connectivity.internet_restored" : "connectivity.internet_lost";
        audit.record(AuditEvent.of(action, "connectivity", null));
    }
}
