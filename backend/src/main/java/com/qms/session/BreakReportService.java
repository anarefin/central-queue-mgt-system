package com.qms.session;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.ScopeGuard;
import com.qms.session.SessionRepository.EndedBreak;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Break time per agent and break type (FR-AGT-022): how many breaks, how long in all and on average, how many ran over their
 * type's maximum. Who runs reports is {@code reports:run_export} (§5.2), and what they see is limited to the sites and Service
 * groups in their token (FR-CFG-106).
 */
@Service
@Profile(Profiles.SERVING)
public class BreakReportService {

    static final String RUN = "hasAuthority(T(com.qms.platform.security.Authorities).REPORTS_RUN_EXPORT)";

    private final SessionRepository sessions;
    private final ScopeGuard scope;

    BreakReportService(SessionRepository sessions, ScopeGuard scope) {
        this.sessions = sessions;
        this.scope = scope;
    }

    /** Breaks started in [from, to) (either may be left out), optionally for one agent and one type. */
    @PreAuthorize(RUN)
    @Transactional(readOnly = true)
    public BreakReport report(Instant from, Instant to, UUID agentId, UUID breakTypeId) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "to", "code", "before_from"))));
        }
        Set<UUID> sites = scope.sites(List.of());
        Set<UUID> groups = scope.groups(List.of());
        List<BreakReport.Taken> reachable = sessions.endedBreaks(from, to, agentId, breakTypeId).stream()
                .filter(b -> sites.isEmpty() || sites.contains(b.siteId()))
                .filter(b -> groups.isEmpty() || b.groupIds().isEmpty() || b.groupIds().stream().anyMatch(groups::contains))
                .map(EndedBreak::taken)
                .toList();
        return new BreakReport(from, to, BreakReport.summarise(reachable));
    }
}
