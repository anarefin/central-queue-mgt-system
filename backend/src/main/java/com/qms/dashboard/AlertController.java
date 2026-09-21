package com.qms.dashboard;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Threshold alerts (SRS §15.4, ticket 47): a Site's current list, and acknowledging one (FR-MON-022). Raising an
 * alert is system-driven ({@link ThresholdAlertScheduler}, {@code com.qms.session.BreakOverrunScheduler}), so this
 * controller only ever reads and acknowledges. */
@RestController
@Profile(Profiles.SERVING)
class AlertController {

    private final AlertReadService reads;
    private final AlertAcknowledgeService acknowledgement;

    AlertController(AlertReadService reads, AlertAcknowledgeService acknowledgement) {
        this.reads = reads;
        this.acknowledgement = acknowledgement;
    }

    @PreAuthorize(AlertReadService.VIEW_OR_ACT)
    @GetMapping("/sites/{site_id}/alerts")
    Items<Alert> list(@PathVariable("site_id") UUID siteId, @RequestParam(required = false) String state) {
        return new Items<>(reads.list(siteId, state));
    }

    @PreAuthorize(AlertReadService.VIEW_OR_ACT)
    @PostMapping("/alerts/{id}/acknowledge")
    Alert acknowledge(@PathVariable UUID id, @RequestBody(required = false) AlertAcknowledgeRequest request) {
        return acknowledgement.acknowledge(id, request == null ? null : request.note());
    }
}
