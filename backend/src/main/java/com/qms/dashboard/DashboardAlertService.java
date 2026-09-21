package com.qms.dashboard;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.realtime.Topics;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one write ticket 46 adds beyond earlier tickets' own endpoints (FR-MON-004): a supervisor's manual staff
 * alert, delivered on the existing {@code staff_alert:{site_id}} channel (ticket 38) since it reaches the same
 * audience (team and org admins of the Site, SRS §14.1) that channel already has a topic for. Its permission is
 * reused rather than invented — neither it nor any of FR-MON-004's other three bundled actions (re-prioritise,
 * force-close a counter, change an Agent's status, all unchanged here, each already gated by its own earlier
 * ticket's permission) has a row of its own in the SRS §5.2 matrix beyond "view the dashboard"; sending an alert is
 * gated by the same {@code dashboard:view_*} reach that already governs who may watch a Site's staff alerts at all
 * ({@code com.qms.notification.NotificationTopics}) — the same "reuse an existing permission for one more read-only
 * [or, here, dashboard-scoped] screen rather than invent one" call {@code com.qms.notification.NotificationLogService}
 * already makes for its own admin view.
 *
 * <p>Kept apart from {@link DashboardReadService}: this class needs {@code RealtimePublisher} (the hub), and
 * {@link DashboardTopics} — one of the beans the hub itself collects — must never depend on anything that does,
 * or Spring's container refuses the resulting circular wiring at startup.
 */
@Service
public class DashboardAlertService {

    /** Sending a staff alert is a supervisor act, not merely a look: the Agent-own tier of dashboard viewing does not
     * reach it, only whoever already views a whole Site or Team's worth of it. */
    static final String SEND_ALERT = "hasAnyAuthority(T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_ALL,"
            + " T(com.qms.platform.security.Authorities).DASHBOARD_VIEW_OWN_GROUPS)";

    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final DashboardReads reads;
    private final CurrentUser currentUser;
    private final ScopeGuard scope;
    private final RealtimePublisher realtime;
    private final AuditWriter audit;
    private final Clock clock;

    DashboardAlertService(DashboardReads reads, CurrentUser currentUser, ScopeGuard scope, RealtimePublisher realtime, AuditWriter audit, Clock clock) {
        this.reads = reads;
        this.currentUser = currentUser;
        this.scope = scope;
        this.realtime = realtime;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(SEND_ALERT)
    @Transactional
    public void sendStaffAlert(UUID siteId, StaffAlertRequest request) {
        scope.requireSite(siteId);
        reads.site(siteId);
        String message = request == null || request.message() == null ? null : request.message().strip();
        if (message == null || message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "message", "code", "required"))));
        }
        UUID actorId = currentUser.require().userId();
        Instant now = clock.instant();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("site_id", siteId.toString());
        data.put("sender_id", actorId.toString());
        data.put("message", message);
        realtime.publish(Topics.staffAlert(siteId), "dashboard.staff_alert_sent", now, data);

        audit.record(AuditEvent.of("dashboard.staff_alert_sent", "site", siteId).withAfter(Map.of("message", message)));
    }
}
