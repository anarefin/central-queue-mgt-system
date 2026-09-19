package com.qms.session;

import com.qms.platform.Profiles;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The break report (FR-AGT-022): {@code GET /break-report?from=&to=&agent_id=&break_type_id=}, times as ISO-8601 instants. */
@RestController
@Profile(Profiles.SERVING)
public class BreakReportController {

    private final BreakReportService service;

    BreakReportController(BreakReportService service) {
        this.service = service;
    }

    @PreAuthorize(BreakReportService.RUN)
    @GetMapping("/break-report")
    public BreakReport report(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "agent_id", required = false) UUID agentId,
            @RequestParam(name = "break_type_id", required = false) UUID breakTypeId) {
        return service.report(from, to, agentId, breakTypeId);
    }
}
