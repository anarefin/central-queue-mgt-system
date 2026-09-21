package com.qms.reporting;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Scheduled report delivery over HTTP (ticket 52, SRS §16, FR-RPT-005): {@code /reports/schedules} CRUD, plus
 * each schedule's own delivery log. */
@RestController
@Profile(Profiles.SERVING)
class ReportScheduleController {

    private final ReportScheduleService service;

    ReportScheduleController(ReportScheduleService service) {
        this.service = service;
    }

    @PreAuthorize(ReportScheduleService.MANAGE)
    @PostMapping("/reports/schedules")
    ReportScheduleView create(@RequestBody ReportScheduleRequest request) {
        return service.create(request);
    }

    @PreAuthorize(ReportScheduleService.VIEW)
    @GetMapping("/reports/schedules")
    Items<ReportScheduleView> list() {
        return new Items<>(service.list());
    }

    @PreAuthorize(ReportScheduleService.VIEW)
    @GetMapping("/reports/schedules/{id}")
    ReportScheduleView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize(ReportScheduleService.MANAGE)
    @PutMapping("/reports/schedules/{id}")
    ReportScheduleView update(@PathVariable UUID id, @RequestBody ReportScheduleRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize(ReportScheduleService.MANAGE)
    @DeleteMapping("/reports/schedules/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PreAuthorize(ReportScheduleService.VIEW)
    @GetMapping("/reports/schedules/{id}/deliveries")
    Items<ReportScheduleDeliveryView> deliveries(@PathVariable UUID id) {
        return new Items<>(service.deliveries(id));
    }
}
