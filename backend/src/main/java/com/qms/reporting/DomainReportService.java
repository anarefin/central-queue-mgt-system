package com.qms.reporting;

import com.qms.audit.AuditFilter;
import com.qms.audit.AuditPage;
import com.qms.audit.AuditQueryService;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * The five report keys ticket 51 adds under {@code POST /reports/{key}/run} (§16.1): {@code appointment}, {@code
 * journey}, {@code feedback}, {@code notification} and {@code audit}. Called only from {@link ReportRunService}'s
 * own already {@code @PreAuthorize(REPORTS_RUN_EXPORT)}-gated entry point, the same "protected only at the one
 * shared entry point" shape {@link OperationalReportService} already has — except {@code audit}, which additionally
 * goes through {@code AuditQueryService#search}'s own {@code @PreAuthorize(AUDIT_READ)} (a stricter, System/Org
 * Admin-only gate the existing {@code /audit} endpoint already carries, SRS §5.2): a Team Admin can run every other
 * key in this catalogue but not the audit one, an explicit, narrower reuse of an existing permission rather than a
 * new one.
 */
@Service
@Profile(Profiles.SERVING)
class DomainReportService {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 1000;

    private final DomainReportReads reads;
    private final AuditQueryService auditQueryService;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    DomainReportService(DomainReportReads reads, AuditQueryService auditQueryService, CurrentUser currentUser, ScopeGuard scope, Clock clock) {
        this.reads = reads;
        this.auditQueryService = auditQueryService;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Object run(DomainReportKey key, ReportRunRequest request) {
        return switch (key) {
            case APPOINTMENT -> appointmentReport(request);
            case JOURNEY -> journeyReport(request);
            case FEEDBACK -> feedbackReport(request);
            case NOTIFICATION -> notificationReport(request);
            case AUDIT -> auditReport(request);
        };
    }

    // ---- appointment (§16.1, FR-APT-043) --------------------------------------------------------------------

    private DomainReportPage appointmentReport(ReportRunRequest request) {
        DetailedTokenReportFilter filter = request.filter();
        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);
        int page = validatePage(request.page());
        int size = validateSize(request.size());

        long totalRows = reads.appointmentTotalRows(filter, allowedSites, allowedGroups);
        List<Map<String, Object>> rows = reads.appointmentPage(filter, allowedSites, allowedGroups, size, page * size);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("no_show_rate_by_service", reads.appointmentNoShowRateByService(filter, allowedSites, allowedGroups));
        extra.put("no_show_rate_by_agent", reads.appointmentNoShowRateByAgent(filter, allowedSites, allowedGroups));
        extra.put("no_show_rate_by_visitor_category", reads.appointmentNoShowRateByVisitorCategory(filter, allowedSites, allowedGroups));
        extra.put("adherence", reads.appointmentAdherence(filter, allowedSites, allowedGroups));

        return new DomainReportPage(
                DomainReportKey.APPOINTMENT.wire(), clock.instant(), page, size, totalRows, totalPages(totalRows, size), rows, extra);
    }

    // ---- journey (§16.1, FR-QUE-064) -------------------------------------------------------------------------

    private DomainReportPage journeyReport(ReportRunRequest request) {
        DetailedTokenReportFilter filter = request.filter();
        Set<UUID> allowedSites = reachSites(filter);
        int page = validatePage(request.page());
        int size = validateSize(request.size());

        long totalRows = reads.journeyTotalRows(filter, allowedSites);
        List<Map<String, Object>> rows = reads.journeyPage(filter, allowedSites, size, page * size);
        Map<String, Object> extra = reads.journeyAggregate(filter, allowedSites);

        return new DomainReportPage(
                DomainReportKey.JOURNEY.wire(), clock.instant(), page, size, totalRows, totalPages(totalRows, size), rows, extra);
    }

    // ---- feedback (§16.1) -------------------------------------------------------------------------------------

    private DomainReportPage feedbackReport(ReportRunRequest request) {
        DetailedTokenReportFilter filter = request.filter();
        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);
        int page = validatePage(request.page());
        int size = validateSize(request.size());

        long totalRows = reads.feedbackTotalRows(filter, allowedSites, allowedGroups);
        List<Map<String, Object>> rows = reads.feedbackPage(filter, allowedSites, allowedGroups, size, page * size);
        Map<String, Object> extra = reads.feedbackTotals(filter, allowedSites, allowedGroups);

        return new DomainReportPage(
                DomainReportKey.FEEDBACK.wire(), clock.instant(), page, size, totalRows, totalPages(totalRows, size), rows, extra);
    }

    // ---- notification (§16.1) --------------------------------------------------------------------------------

    private DomainReportPage notificationReport(ReportRunRequest request) {
        DetailedTokenReportFilter filter = request.filter();
        Set<UUID> allowedSites = reachSites(filter);
        int page = validatePage(request.page());
        int size = validateSize(request.size());

        long totalRows = reads.notificationTotalRows(filter, allowedSites);
        List<Map<String, Object>> rows = reads.notificationPage(filter, allowedSites, size, page * size);

        return new DomainReportPage(
                DomainReportKey.NOTIFICATION.wire(), clock.instant(), page, size, totalRows, totalPages(totalRows, size), rows, null);
    }

    // ---- audit (§16.1, FR-SEC-042): reuses AuditQueryService's own cursor-paged, AUDIT_READ-gated read ----------

    private AuditReportPage auditReport(ReportRunRequest request) {
        AuditFilter filter = new AuditFilter(null, null, null, null, toOffset(request.from()), toOffset(request.to()));
        AuditPage page = auditQueryService.search(filter, request.cursor(), request.size());
        return new AuditReportPage(DomainReportKey.AUDIT.wire(), clock.instant(), page.items(), page.nextCursor());
    }

    private static OffsetDateTime toOffset(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    // ---- shared validation and scope (the same shape ReportRunService's own detailed-token path uses) -----------

    private int totalPages(long totalRows, int size) {
        return totalRows == 0 ? 0 : (int) ((totalRows + size - 1) / size);
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

    private void fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }
}
