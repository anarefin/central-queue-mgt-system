package com.qms.dashboard;

import com.qms.platform.Profiles;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live dashboard over HTTP (SRS §15.1, ticket 46): {@code GET /dashboard/live}, filterable and shareable as a
 * URL (FR-MON-002), and the one supervisor act this ticket adds beyond what earlier tickets' own endpoints already
 * cover (re-prioritise: {@code POST /tickets/{id}/priority}; force-close a counter: {@code POST
 * /sessions/{id}/force-close}; change an Agent's status: {@code PUT /agents/{id}/availability}) — sending a staff
 * alert.
 */
@RestController
@Profile(Profiles.SERVING)
class DashboardController {

    private final DashboardReadService reads;
    private final DashboardAlertService alerts;

    DashboardController(DashboardReadService reads, DashboardAlertService alerts) {
        this.reads = reads;
        this.alerts = alerts;
    }

    @PreAuthorize(DashboardReadService.VIEW)
    @GetMapping("/dashboard/live")
    Map<String, Object> live(
            @RequestParam(name = "site_id") UUID siteId,
            @RequestParam(name = "zone_id", required = false) UUID zoneId,
            @RequestParam(name = "service_group_id", required = false) UUID serviceGroupId,
            @RequestParam(name = "service_id", required = false) UUID serviceId,
            @RequestParam(name = "priority_class_id", required = false) UUID priorityClassId) {
        return reads.live(new DashboardFilter(siteId, zoneId, serviceGroupId, serviceId, priorityClassId));
    }

    @PreAuthorize(DashboardAlertService.SEND_ALERT)
    @PostMapping("/dashboard/{site_id}/staff-alert")
    void staffAlert(@PathVariable("site_id") UUID siteId, @RequestBody(required = false) StaffAlertRequest request) {
        alerts.sendStaffAlert(siteId, request);
    }
}
