package com.qms.platform.realtime;

import java.util.UUID;

/** The topic names of SRS §21.2 that have a publisher so far. */
public final class Topics {

    public static final String QUEUE = "queue:";
    public static final String COUNTER = "counter:";
    public static final String DEVICE = "device:";
    public static final String ZONE = "zone:";
    public static final String TICKET = "ticket:";
    public static final String STAFF_ALERT = "staff-alert:";
    public static final String SITE_PREFIX = "site:";
    public static final String DASHBOARD_SUFFIX = ":dashboard";
    public static final String ALERTS_SUFFIX = ":alerts";
    public static final String REPORT_EXPORT = "report-export:";

    private Topics() {}

    public static String queue(UUID serviceId) {
        return QUEUE + serviceId;
    }

    public static String counter(UUID counterId) {
        return COUNTER + counterId;
    }

    /** Config change, reload and revoke commands for one device (§21.2, FR-OPS-042). */
    public static String device(UUID deviceId) {
        return DEVICE + deviceId;
    }

    /** A zone's display board(s): the serving table and next-token strip of every counter in it (§21.2, ticket 28, FR-DSP-010). */
    public static String zone(UUID zoneId) {
        return ZONE + zoneId;
    }

    /** One Ticket's own state, position and estimate: the visitor ticket page (§21.2, ticket 37, FR-MOB-013). */
    public static String ticket(UUID ticketId) {
        return TICKET + ticketId;
    }

    /** A Site's staff alerts: the {@code staff_alert} notification channel (ticket 38, SRS §14.1, FR-NTF-005). */
    public static String staffAlert(UUID siteId) {
        return STAFF_ALERT + siteId;
    }

    /** A Site's live dashboard (ticket 46, §21.2, FR-MON-001): a refresh signal, not the tile data itself — a
     * subscriber whose reach is scoped to their own Service groups (FR-CFG-105) must always pull the payload through
     * its own authenticated {@code GET /dashboard/live}, never a broadcast the hub fans out unfiltered to every
     * subscriber of the topic. */
    public static String dashboard(UUID siteId) {
        return SITE_PREFIX + siteId + DASHBOARD_SUFFIX;
    }

    /** A Site's threshold alerts (ticket 47, §21.2, §15.4): {@code alert.raised} and {@code alert.acknowledged},
     * distinct from {@link #staffAlert(UUID)}'s own free-text/notification firehose — this one carries the
     * persisted, acknowledgeable {@code alert} row itself. */
    public static String alerts(UUID siteId) {
        return SITE_PREFIX + siteId + ALERTS_SUFFIX;
    }

    /** A staff user's own background report exports (ticket 49, FR-RPT-004): the "notified" of an async export's
     * expiring download link, watched only by the user who requested it — never a Site-wide broadcast, since two
     * users asking for the same report do not each want the other's job. */
    public static String reportExport(UUID userId) {
        return REPORT_EXPORT + userId;
    }
}
