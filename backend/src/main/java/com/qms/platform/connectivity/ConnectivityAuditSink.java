package com.qms.platform.connectivity;

/**
 * Where {@link InternetConnectivityMonitor} reports a reachability transition (ticket 44, SRS §27.5 "specified
 * events and audit entries"): implemented once, by {@code com.qms.audit}, the same seam {@code
 * com.qms.platform.notifications.NotificationTrigger} already gives the notification pipeline — platform defines the
 * seam, a bounded context supplies it, so platform never depends on one (ArchUnit).
 */
public interface ConnectivityAuditSink {

    /** {@code reachable} is the state just entered, i.e. what {@link InternetConnectivityMonitor#reachable()} now returns. */
    void recordTransition(boolean reachable);
}
