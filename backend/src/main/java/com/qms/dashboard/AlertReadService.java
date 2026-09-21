package com.qms.dashboard;

import com.qms.platform.security.ScopeGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Site's current threshold alerts (SRS §15.4, ticket 47): reuses the reach {@code dashboard:view_*} already
 * governs — whoever watches a Site's live dashboard (all groups for Org/System Admin, own groups for Team Admin,
 * §5.2) may see its alerts, since neither has a row of its own in the SRS §5.2 matrix. Kept apart from {@link
 * AlertAcknowledgeService}, which needs {@code RealtimePublisher} (the hub), so that {@link AlertTopics} — itself
 * one of the beans the hub collects — can depend on this class without the hub ending up depending on itself
 * through it (Spring refuses that circular wiring at startup), the same split {@link DashboardReadService} and
 * {@link DashboardAlertService} already make.
 */
@Service
public class AlertReadService {

    static final String VIEW_OR_ACT = "hasAnyAuthority(T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_ALL,"
            + " T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_OWN_GROUPS)";

    private final AlertRepository repository;
    private final ScopeGuard scope;

    AlertReadService(AlertRepository repository, ScopeGuard scope) {
        this.repository = repository;
        this.scope = scope;
    }

    @PreAuthorize(VIEW_OR_ACT)
    @Transactional(readOnly = true)
    public List<Alert> list(UUID siteId, String state) {
        scope.requireSite(siteId);
        return repository.forSite(siteId, state);
    }

    /** What a subscriber of {@code site:{id}:alerts} is shown first (§21.1): the Site's own currently-open alerts,
     * under the same reach {@link #list} already checked by {@link AlertTopics#authorize}. */
    List<Alert> snapshotForSubscriber(UUID siteId) {
        return repository.forSite(siteId, Alert.OPEN);
    }
}
