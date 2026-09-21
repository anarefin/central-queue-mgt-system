package com.qms.reporting;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two staffing-planning views ticket 51 adds under {@code POST /reports/{key}/run}: {@code peak-hours}
 * (FR-RPT-011) and {@code staffing-gap} (FR-RPT-012). Called only from {@link ReportRunService}'s own already
 * {@code @PreAuthorize(REPORTS_RUN_EXPORT)}-gated entry point, the same shape every other key in this catalogue
 * already has.
 */
@Service
@Profile(Profiles.SERVING)
class PlanningViewService {

    private final PlanningViewReads reads;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final Clock clock;

    PlanningViewService(PlanningViewReads reads, CurrentUser currentUser, ScopeGuard scope, Clock clock) {
        this.reads = reads;
        this.currentUser = currentUser;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Object run(PlanningViewKey key, ReportRunRequest request) {
        DetailedTokenReportFilter filter = request.filter();
        if (filter.from() == null || filter.to() == null) fail("to", "range_required");
        if (!filter.to().isAfter(filter.from())) fail("to", "before_from");

        Set<UUID> allowedSites = reachSites(filter);
        Set<UUID> allowedGroups = reachGroups(filter);

        return switch (key) {
            case PEAK_HOURS -> new PeakHoursResponse(
                    key.wire(), clock.instant(), filter.from(), filter.to(), reads.peakHours(filter, allowedSites, allowedGroups));
            case STAFFING_GAP -> new StaffingGapResponse(
                    key.wire(), clock.instant(), filter.from(), filter.to(), reads.staffingGap(filter, allowedSites, allowedGroups, clock.instant()));
        };
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

    private void fail(String field, String code) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("field", field, "code", code));
        throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
    }
}
