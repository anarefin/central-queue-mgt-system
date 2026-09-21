package com.qms.dashboard;

import com.qms.dashboard.DashboardReads.Appointments;
import com.qms.dashboard.DashboardReads.Counters;
import com.qms.dashboard.DashboardReads.CounterThroughput;
import com.qms.dashboard.DashboardReads.DeviceHealth;
import com.qms.dashboard.DashboardReads.LongestWait;
import com.qms.dashboard.DashboardReads.RemoteQueue;
import com.qms.dashboard.DashboardReads.ServingTicket;
import com.qms.dashboard.DashboardReads.SiteRow;
import com.qms.dashboard.DashboardReads.Throughput;
import com.qms.dashboard.DashboardReads.WaitingGroup;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live dashboard's read model (ticket 46, SRS §15.1): {@link DashboardReads} assembled under the caller's scope
 * (FR-CFG-105, FR-CFG-106) into the one shape both {@code GET /dashboard/live} ({@link DashboardController}) and the
 * {@code site:{id}:dashboard} topic's own subscribe-time snapshot ({@link DashboardTopics}) return. Kept apart from
 * {@link DashboardAlertService}, which needs {@code RealtimePublisher}, so that {@link DashboardTopics} — itself one
 * of the beans {@code RealtimeHub} collects — can depend on this class without the hub ending up depending on
 * itself through it (Spring refuses that circular wiring at startup).
 */
@Service
public class DashboardReadService {

    static final String VIEW = "hasAnyAuthority(T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_ALL,"
            + " T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_OWN_GROUPS,"
            + " T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_OWN_GROUPS + ':own')";

    private final DashboardReads reads;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    DashboardReadService(DashboardReads reads, CurrentUser currentUser, ScopeGuard scope, Clock clock) {
        this.reads = reads;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @PreAuthorize(VIEW)
    @Transactional(readOnly = true)
    public Map<String, Object> live(DashboardFilter filter) {
        return snapshot(filter, reachGroups(filter));
    }

    /** What a subscriber of {@code site:{id}:dashboard} is shown first (§21.1): the caller's own reach, with no
     * further filter — the refinement filters of {@code GET /dashboard/live} are a client-side concern once it has
     * this (FR-MON-002's "shareable as a URL" is the browser's query string, not a second server-side view). */
    Map<String, Object> snapshotForSubscriber(UUID siteId) {
        DashboardFilter filter = new DashboardFilter(siteId, null, null, null, null);
        return snapshot(filter, reachGroups(filter));
    }

    private List<UUID> reachGroups(DashboardFilter filter) {
        AuthenticatedUser user = currentUser.require();
        scope.requireSite(filter.siteId());
        if (filter.serviceGroupId() != null) {
            scope.requireGroup(filter.serviceGroupId());
            return null;
        }
        return user.groupIds().isEmpty() ? null : List.copyOf(user.groupIds());
    }

    private Map<String, Object> snapshot(DashboardFilter filter, List<UUID> allowedGroups) {
        SiteRow site = reads.site(filter.siteId());
        if (filter.zoneId() != null) reads.requireZoneInSite(filter.zoneId(), filter.siteId());
        if (filter.serviceGroupId() != null) reads.requireGroupInSite(filter.serviceGroupId(), filter.siteId());

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, site.timezone());
        Instant todayStart = today.atStartOfDay(site.timezone()).toInstant();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site_id", filter.siteId().toString());
        body.put("zone_id", string(filter.zoneId()));
        body.put("service_group_id", string(filter.serviceGroupId()));
        body.put("service_id", string(filter.serviceId()));
        body.put("priority_class_id", string(filter.priorityClassId()));
        body.put("generated_at", now.toString());
        body.put("waiting_now", waitingNow(reads.waitingNow(filter, allowedGroups, now)));
        body.put("serving_now", servingNow(reads.servingNow(filter, allowedGroups, now)));
        body.put("counters", counters(reads.counters(filter)));
        body.put("longest_waits", longestWaits(reads.longestWaits(filter, allowedGroups, now)));
        body.put("throughput_today", throughput(reads.throughputToday(filter, allowedGroups, todayStart)));
        body.put("appointments_today", appointments(reads.appointmentsToday(filter, allowedGroups, today, site.timezone(), now)));
        body.put("remote_queue", remoteQueue(reads.remoteQueue(filter, allowedGroups, todayStart)));
        body.put("device_health", deviceHealth(reads.deviceHealth(filter, now)));
        body.put("served_per_open_counter", servedPerOpenCounter(reads.servedPerOpenCounter(filter, todayStart)));
        return body;
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }

    private static List<Map<String, Object>> waitingNow(List<WaitingGroup> groups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WaitingGroup g : groups) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service_group_id", g.serviceGroupId().toString());
            row.put("service_group_name", g.names());
            row.put("count", g.count());
            row.put("longest_wait_seconds", g.longestWaitSeconds());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> servingNow(List<ServingTicket> tickets) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServingTicket s : tickets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticket_id", s.ticketId().toString());
            row.put("token_number", s.tokenNumber());
            row.put("counter_id", s.counterId().toString());
            row.put("counter_label", s.counterLabel());
            row.put("agent_id", string(s.agentId()));
            row.put("agent_name", s.agentName());
            row.put("elapsed_seconds", s.elapsedSeconds());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> counters(Counters c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("open", c.open());
        row.put("on_break", c.onBreak());
        row.put("closed", c.closed());
        row.put("idle_with_queue", c.idleWithQueue());
        return row;
    }

    private static List<Map<String, Object>> longestWaits(List<LongestWait> waits) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LongestWait w : waits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticket_id", w.ticketId().toString());
            row.put("token_number", w.tokenNumber());
            row.put("service_id", w.serviceId().toString());
            row.put("service_name", w.serviceNames());
            row.put("wait_seconds", w.waitSeconds());
            row.put("escalated", w.escalated());
            row.put("sla_breached", w.slaBreached());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> throughput(Throughput t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("served", t.served());
        row.put("cancelled", t.cancelled());
        row.put("no_show", t.noShow());
        row.put("transferred", t.transferred());
        return row;
    }

    private static Map<String, Object> appointments(Appointments a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("booked", a.booked());
        row.put("checked_in", a.checkedIn());
        row.put("no_show", a.noShow());
        row.put("upcoming_next_hour", a.upcomingNextHour());
        return row;
    }

    private static Map<String, Object> remoteQueue(RemoteQueue r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("remote", r.remote());
        row.put("approaching", r.approaching());
        row.put("present", r.present());
        row.put("forfeited", r.forfeited());
        return row;
    }

    private static Map<String, Object> deviceHealth(DeviceHealth d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kiosks_offline", d.kiosksOffline());
        row.put("displays_offline", d.displaysOffline());
        // Printer devices are not modelled yet (Phase 2, com.qms.device.HeartbeatRequest's own note): an empty state
        // rather than a fabricated count, per this ticket's own "tiles for features not yet built show empty state".
        row.put("printers_offline", null);
        return row;
    }

    private static List<Map<String, Object>> servedPerOpenCounter(List<CounterThroughput> counters) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CounterThroughput c : counters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("counter_id", c.counterId().toString());
            row.put("label", c.label());
            row.put("session_id", string(c.sessionId()));
            row.put("served_count", c.servedCount());
            rows.add(row);
        }
        return rows;
    }
}
