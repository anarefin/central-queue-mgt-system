package com.qms.reporting;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /reports/{key}/run} (ticket 48, §16): the one report this ticket's catalogue holds, the detailed
 * token report (§16.1). Later tickets (49, 50, 51) grow the catalogue; the key is checked against it up front so an
 * unknown one is {@code not_found} rather than silently answering the wrong shape.
 */
@Service
public class ReportRunService {

    static final String RUN = "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)";

    static final String DETAILED_TOKEN_KEY = "detailed-token";

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 1000;

    private final DetailedTokenReportReads reads;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    ReportRunService(DetailedTokenReportReads reads, CurrentUser currentUser, ScopeGuard scope, Clock clock) {
        this.reads = reads;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @PreAuthorize(RUN)
    @Transactional(readOnly = true)
    public DetailedTokenReportPage run(String key, DetailedTokenReportRequest request) {
        if (!DETAILED_TOKEN_KEY.equals(key)) throw new ApiException(ErrorCode.NOT_FOUND);
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
