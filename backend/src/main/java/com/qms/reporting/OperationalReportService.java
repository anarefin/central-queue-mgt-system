package com.qms.reporting;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import com.qms.session.BreakReport;
import com.qms.session.BreakReportService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The six operational report keys ticket 50 adds (§16.1's "Visitor flow", "Counter", "Agent", "Service",
 * "Department" and "Site" rows; the "Break" row of the same catalogue is {@code com.qms.session.BreakReportService},
 * unchanged since ticket 16 and reused as-is by {@link ReportRunService}). Every key shares one shape: rows for the
 * requested period at that report's own grain, plus a {@code totals}/{@code previous_totals}/{@code change} block
 * comparing the whole period against the immediately preceding period of the same length (FR-RPT-010, FR-MON-011) —
 * unlike {@code detailed-token}, both {@code from} and {@code to} are mandatory here, since a comparison needs a
 * bounded period to mirror.
 *
 * <p>{@code @Profile(SERVING)}: depends on {@code BreakReportService}, itself {@code SERVING}-only.
 */
@Service
@Profile(Profiles.SERVING)
class OperationalReportService {

    private static final Set<String> GRAINS = Set.of("hour", "day", "month", "year");

    private final OperationalReportReads reads;
    private final BreakReportService breakReports;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    OperationalReportService(OperationalReportReads reads, BreakReportService breakReports, CurrentUser currentUser, ScopeGuard scope, Clock clock) {
        this.reads = reads;
        this.breakReports = breakReports;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    OperationalReportResponse run(OperationalReportKey key, ReportRunRequest request) {
        DetailedTokenReportFilter filter = request.filter();
        if (filter.from() == null || filter.to() == null) fail("to", "range_required");
        if (!filter.to().isAfter(filter.from())) fail("to", "before_from");
        String grain = key == OperationalReportKey.VISITOR_FLOW ? validateGrain(request.grain()) : null;

        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);

        Duration span = Duration.between(filter.from(), filter.to());
        Instant previousTo = filter.from();
        Instant previousFrom = previousTo.minus(span);
        DetailedTokenReportFilter previousFilter = new DetailedTokenReportFilter(
                previousFrom, previousTo, filter.siteId(), filter.zoneId(), filter.serviceGroupId(), filter.serviceId(),
                filter.agentId(), filter.priorityClassId(), filter.channel(), filter.visitorCategory());

        List<Map<String, Object>> rows;
        Map<String, Object> totals;
        Map<String, Object> previousTotals;
        Map<String, Object> extra = null;
        Instant now = clock.instant();

        switch (key) {
            case VISITOR_FLOW -> {
                rows = reads.visitorFlowRows(filter, allowedSites, allowedGroups, grain);
                totals = reads.visitorFlowTotals(filter, allowedSites, allowedGroups);
                previousTotals = reads.visitorFlowTotals(previousFilter, allowedSites, allowedGroups);
                extra = Map.of("channel_mix", reads.channelMix(filter, allowedSites, allowedGroups));
            }
            case COUNTER -> {
                rows = reads.counterRows(filter, allowedSites, allowedGroups, now);
                totals = reads.counterTotals(filter, allowedSites, allowedGroups, now);
                previousTotals = reads.counterTotals(previousFilter, allowedSites, allowedGroups, now);
            }
            case AGENT -> {
                rows = agentRows(filter, allowedSites, allowedGroups, now);
                totals = agentTotals(filter, allowedSites, allowedGroups, now);
                previousTotals = agentTotals(previousFilter, allowedSites, allowedGroups, now);
            }
            case SERVICE -> {
                rows = reads.serviceRows(filter, allowedSites, allowedGroups);
                totals = reads.serviceTotals(filter, allowedSites, allowedGroups);
                previousTotals = reads.serviceTotals(previousFilter, allowedSites, allowedGroups);
                extra = Map.of("wait_by_hour_band", reads.waitByHourBand(filter, allowedSites, allowedGroups));
            }
            case DEPARTMENT -> {
                rows = reads.departmentRows(filter, allowedSites, allowedGroups);
                totals = reads.departmentTotals(filter, allowedSites, allowedGroups);
                previousTotals = reads.departmentTotals(previousFilter, allowedSites, allowedGroups);
            }
            case SITE -> {
                rows = reads.siteRows(filter, allowedSites, allowedGroups);
                totals = reads.siteTotals(filter, allowedSites, allowedGroups);
                previousTotals = reads.siteTotals(previousFilter, allowedSites, allowedGroups);
            }
            default -> throw new ApiException(ErrorCode.NOT_FOUND);
        }

        Map<String, Object> change = ChangeCalculator.changes(totals, previousTotals);
        return new OperationalReportResponse(
                key.wire(), now, filter.from(), filter.to(), previousFrom, previousTo, rows, totals, previousTotals, change, extra);
    }

    // ---- agent report: ticket stats + live session time (login adherence) + the existing break report ----------

    private List<Map<String, Object>> agentRows(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now) {
        List<Map<String, Object>> ticketStats = reads.agentTicketStats(filter, allowedSites, allowedGroups);
        Map<UUID, Long> openSecondsByAgent = new LinkedHashMap<>();
        Map<UUID, UUID> primarySiteByAgent = new LinkedHashMap<>();
        primarySites(filter, allowedSites, allowedGroups, now, openSecondsByAgent, primarySiteByAgent);
        Map<UUID, double[]> breakByAgent = breakStatsByAgent(filter.from(), filter.to());
        Map<UUID, Long> businessHoursCache = new LinkedHashMap<>();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> stats : ticketStats) {
            Map<String, Object> row = new LinkedHashMap<>(stats);
            UUID agentId = (UUID) row.get("agent_id");
            addAgentComputedFields(row, agentId, openSecondsByAgent, primarySiteByAgent, breakByAgent, businessHoursCache, filter.from(), filter.to());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> agentTotals(DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now) {
        Map<String, Object> row = new LinkedHashMap<>(reads.agentTicketStatsTotal(filter, allowedSites, allowedGroups));
        Map<UUID, Long> openSecondsByAgent = new LinkedHashMap<>();
        Map<UUID, UUID> primarySiteByAgent = new LinkedHashMap<>();
        primarySites(filter, allowedSites, allowedGroups, now, openSecondsByAgent, primarySiteByAgent);
        Map<UUID, Long> businessHoursCache = new LinkedHashMap<>();

        long totalOpenSeconds = 0;
        long totalRostered = 0;
        for (Map.Entry<UUID, Long> entry : openSecondsByAgent.entrySet()) {
            UUID agentId = entry.getKey();
            totalOpenSeconds += entry.getValue();
            UUID primarySite = primarySiteByAgent.get(agentId);
            totalRostered += primarySite == null ? 0 : rosteredSeconds(primarySite, filter.from(), filter.to(), businessHoursCache);
        }
        row.put("login_adherence_pct", totalRostered > 0 ? round2(totalOpenSeconds * 100.0 / totalRostered) : null);

        BreakReport wholeOrg = breakReports.report(filter.from(), filter.to(), null, null);
        long totalBreakSeconds = 0;
        int totalBreakCount = 0;
        for (BreakReport.Row breakRow : wholeOrg.rows()) {
            totalBreakSeconds += breakRow.totalSeconds();
            totalBreakCount += breakRow.count();
        }
        row.put("avg_break_seconds", totalBreakCount > 0 ? round2((double) totalBreakSeconds / totalBreakCount) : null);

        long served = ((Number) row.getOrDefault("services_served", 0L)).longValue();
        long cancelled = ((Number) row.getOrDefault("services_cancelled", 0L)).longValue();
        row.put("successful_token_rate_pct", (served + cancelled) > 0 ? round2(served * 100.0 / (served + cancelled)) : null);
        return row;
    }

    private void addAgentComputedFields(
            Map<String, Object> row, UUID agentId, Map<UUID, Long> openSecondsByAgent, Map<UUID, UUID> primarySiteByAgent,
            Map<UUID, double[]> breakByAgent, Map<UUID, Long> businessHoursCache, Instant from, Instant to) {
        long open = openSecondsByAgent.getOrDefault(agentId, 0L);
        UUID primarySite = primarySiteByAgent.get(agentId);
        long rostered = primarySite == null ? 0 : rosteredSeconds(primarySite, from, to, businessHoursCache);
        row.put("login_adherence_pct", rostered > 0 ? round2(open * 100.0 / rostered) : null);

        double[] breakStats = breakByAgent.get(agentId);
        row.put("avg_break_seconds", breakStats == null || breakStats[1] == 0 ? null : round2(breakStats[0] / breakStats[1]));

        long served = ((Number) row.getOrDefault("services_served", 0L)).longValue();
        long cancelled = ((Number) row.getOrDefault("services_cancelled", 0L)).longValue();
        row.put("successful_token_rate_pct", (served + cancelled) > 0 ? round2(served * 100.0 / (served + cancelled)) : null);
    }

    /** Fills {@code openSecondsByAgent} (total, across every Site the Agent had a session at) and {@code
     * primarySiteByAgent} (whichever Site holds the most of that Agent's open seconds — §28.3's own note that
     * staff rostering is out of scope for v1 leaves this the closest available stand-in for a "home Site"). */
    private void primarySites(
            DetailedTokenReportFilter filter, Set<UUID> allowedSites, Set<UUID> allowedGroups, Instant now,
            Map<UUID, Long> openSecondsByAgent, Map<UUID, UUID> primarySiteByAgent) {
        Map<UUID, Long> bestPerAgent = new LinkedHashMap<>();
        for (Object[] row : reads.agentSessionSeconds(filter, allowedSites, allowedGroups, now)) {
            UUID agentId = (UUID) row[0];
            UUID siteId = (UUID) row[1];
            long seconds = (Long) row[2];
            if (agentId == null) continue;
            openSecondsByAgent.merge(agentId, seconds, Long::sum);
            if (siteId != null && seconds > bestPerAgent.getOrDefault(agentId, -1L)) {
                bestPerAgent.put(agentId, seconds);
                primarySiteByAgent.put(agentId, siteId);
            }
        }
    }

    private long rosteredSeconds(UUID siteId, Instant from, Instant to, Map<UUID, Long> cache) {
        return cache.computeIfAbsent(siteId, id -> RosteredHours.seconds(reads.businessHours(id), from, to));
    }

    /** {@code agent_id -> [totalSeconds, count]}, rolled up from the existing break report's own per-agent-per-type
     * rows (never re-querying {@code break_record} directly — {@code BreakReportService} already owns that read). */
    private Map<UUID, double[]> breakStatsByAgent(Instant from, Instant to) {
        Map<UUID, double[]> byAgent = new LinkedHashMap<>();
        for (BreakReport.Row row : breakReports.report(from, to, null, null).rows()) {
            double[] totals = byAgent.computeIfAbsent(row.agentId(), a -> new double[2]);
            totals[0] += row.totalSeconds();
            totals[1] += row.count();
        }
        return byAgent;
    }

    // ---- shared validation and scope (the same shape ReportRunService's own detailed-token path uses) -----------

    private String validateGrain(String grain) {
        String value = grain == null || grain.isBlank() ? "day" : grain.toLowerCase(java.util.Locale.ROOT);
        if (!GRAINS.contains(value)) fail("grain", "unknown_value");
        return value;
    }

    private Set<UUID> reachSites(DetailedTokenReportFilter filter) {
        AuthenticatedUser user = currentUser.require();
        if (filter.siteId() != null) {
            scope.requireSite(filter.siteId());
            return null;
        }
        return user.siteIds().isEmpty() ? null : Set.copyOf(user.siteIds());
    }

    private Set<UUID> reachGroups(DetailedTokenReportFilter filter) {
        AuthenticatedUser user = currentUser.require();
        if (filter.serviceGroupId() != null) {
            scope.requireGroup(filter.serviceGroupId());
            return null;
        }
        return user.groupIds().isEmpty() ? null : Set.copyOf(user.groupIds());
    }

    private static void fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }

    private static Double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
