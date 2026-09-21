package com.qms.reporting;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Reports over HTTP (ticket 48, 50, SRS §16): {@code POST /reports/{key}/run}, filtered, paged and sorted on
 * screen for {@code detailed-token} (ticket 48), grouped with a period comparison for the six operational report
 * keys (ticket 50), or the existing break report (ticket 16) reused under this catalogue's own path. */
@RestController
@Profile(Profiles.SERVING)
class ReportController {

    private final ReportRunService service;

    ReportController(ReportRunService service) {
        this.service = service;
    }

    @PreAuthorize(ReportRunService.RUN)
    @PostMapping("/reports/{key}/run")
    Object run(@PathVariable String key, @RequestBody(required = false) ReportRunRequest request) {
        return service.run(key, request);
    }
}
