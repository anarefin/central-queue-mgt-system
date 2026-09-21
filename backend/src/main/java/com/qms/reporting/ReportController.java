package com.qms.reporting;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Reports over HTTP (ticket 48, SRS §16): {@code POST /reports/{key}/run}, filtered, paged and sorted on screen. */
@RestController
@Profile(Profiles.SERVING)
class ReportController {

    private final ReportRunService service;

    ReportController(ReportRunService service) {
        this.service = service;
    }

    @PreAuthorize(ReportRunService.RUN)
    @PostMapping("/reports/{key}/run")
    DetailedTokenReportPage run(@PathVariable String key, @RequestBody(required = false) DetailedTokenReportRequest request) {
        return service.run(key, request);
    }
}
