package com.qms.reporting;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import com.qms.session.BreakReportService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /reports/{key}/run} (ticket 48, 50, §16): dispatches across the whole report catalogue by key —
 * {@code detailed-token} (ticket 48, one row per ticket), {@code break} (ticket 16's {@link BreakReportService},
 * reused unchanged) and the six operational keys {@link OperationalReportKey} names (ticket 50). An unknown key is
 * {@code not_found} rather than silently answering the wrong shape, the same rule ticket 48's own comment
 * ("Later tickets (49, 50, 51) grow the catalogue") anticipated this dispatch would grow to enforce.
 *
 * <p>{@code @Profile(SERVING)} — the same restriction {@link ReportController} and {@link BreakReportService}
 * already carry — because this now depends on {@code BreakReportService}, which only exists in that profile; the
 * {@code migrate}/{@code rotate-keys} profiles never serve a request, so this bean would otherwise fail to wire.
 */
@Service
@Profile(Profiles.SERVING)
public class ReportRunService {

    static final String RUN = "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)";

    static final String DETAILED_TOKEN_KEY = "detailed-token";

    static final String BREAK_KEY = "break";

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 1000;

    private final DetailedTokenReportReads reads;
    private final OperationalReportService operational;
    private final BreakReportService breakReports;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    ReportRunService(
            DetailedTokenReportReads reads,
            OperationalReportService operational,
            BreakReportService breakReports,
            CurrentUser currentUser,
            ScopeGuard scope,
            Clock clock) {
        this.reads = reads;
        this.operational = operational;
        this.breakReports = breakReports;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @PreAuthorize(RUN)
    @Transactional(readOnly = true)
    public Object run(String key, ReportRunRequest request) {
        ReportRunRequest r = request == null ? ReportRunRequest.EMPTY : request;
        if (DETAILED_TOKEN_KEY.equals(key)) return runDetailedToken(r.asDetailedToken());
        if (BREAK_KEY.equals(key)) return breakReports.report(r.from(), r.to(), r.agentId(), r.breakTypeId());
        return OperationalReportKey.fromWire(key)
                .map(opKey -> operational.run(opKey, r))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private DetailedTokenReportPage runDetailedToken(DetailedTokenReportRequest request) {
        DetailedTokenReportRequest r = request == null ? DetailedTokenReportRequest.EMPTY : request;
        DetailedTokenReportFilter filter = r.filter();
        validateRange(filter);

        int page = validatePage(r.page());
        int size = validateSize(r.size());
        String sortKey = r.sort() == null ? DetailedTokenReportReads.DEFAULT_SORT : r.sort();
        String sortColumn = validateSort(sortKey);
        boolean ascending = validateDirection(r.direction());

        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);

        long totalRows = reads.totalRows(filter, allowedSites, allowedGroups);
        long ticketsIssued = reads.ticketsIssued(filter, allowedSites, allowedGroups);
        List<DetailedTokenReportReads.Row> rows = reads.page(filter, allowedSites, allowedGroups, sortColumn, ascending, size, page * size);
        long totalPages = totalRows == 0 ? 0 : ((totalRows + size - 1) / size);

        return new DetailedTokenReportPage(DETAILED_TOKEN_KEY, clock.instant(), page, size, totalRows, totalPages, ticketsIssued, rows.stream().map(this::toRow).toList());
    }

    private DetailedTokenReportPage.Row toRow(DetailedTokenReportReads.Row row) {
        return new DetailedTokenReportPage.Row(
                row.ticketId(), row.tokenNumber(), row.visitorCode(), row.visitorName(), row.visitorCategory(),
                row.serviceGroupName(), row.serviceName(), row.channel(), row.priorityClassName(),
                row.issuedAt(), row.calledAt(), row.servedAt(), row.closedAt(), row.waitSeconds(), row.serviceSeconds(),
                row.counterLabel(), row.agentName(), row.outcomeLabel(), row.transfers());
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

    private void validateRange(DetailedTokenReportFilter filter) {
        if (filter.from() != null && filter.to() != null && !filter.to().isAfter(filter.from())) {
            fail("to", "before_from");
        }
    }

    private int validatePage(Integer page) {
        if (page == null) return 0;
        if (page < 0) fail("page", "negative");
        return page;
    }

    private int validateSize(Integer size) {
        if (size == null) return DEFAULT_SIZE;
        if (size < 1 || size > MAX_SIZE) fail("size", "out_of_range");
        return size;
    }

    private String validateSort(String sortKey) {
        String column = DetailedTokenReportReads.SORT_COLUMNS.get(sortKey);
        if (column == null) fail("sort", "unknown_column");
        return column;
    }

    private boolean validateDirection(String direction) {
        if (direction == null || direction.isBlank()) return false;
        String normalised = direction.toLowerCase(java.util.Locale.ROOT);
        if ("desc".equals(normalised)) return false;
        if ("asc".equals(normalised)) return true;
        fail("direction", "unknown_value");
        return false;
    }

    private void fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }
}
