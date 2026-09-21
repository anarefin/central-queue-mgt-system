package com.qms.reporting;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Report exports over HTTP (ticket 49, SRS §16, FR-RPT-003/004): {@code POST /reports/{key}/export} answers the
 * file directly (200) when it was generated inline, or a job id to poll (202) when it was queued; {@code GET
 * /reports/jobs/{id}} and {@code .../download} are that job's status and, once {@code done}, its expiring link. */
@RestController
@Profile(Profiles.SERVING)
class ReportExportController {

    private final ReportExportService service;

    ReportExportController(ReportExportService service) {
        this.service = service;
    }

    @PreAuthorize(ReportExportService.EXPORT)
    @PostMapping("/reports/{key}/export")
    ResponseEntity<?> export(@PathVariable String key, @RequestBody(required = false) ReportExportRequest request) {
        ReportExportService.Outcome outcome = service.export(key, request);
        if (outcome instanceof ReportExportService.Outcome.Ready ready) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(ready.format().contentType()))
                    .header("Content-Disposition", "attachment; filename=\"" + ready.filename() + "\"")
                    .body(ready.content());
        }
        ReportExportService.Outcome.Queued queued = (ReportExportService.Outcome.Queued) outcome;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ReportExportJobResponse(queued.jobId(), "queued"));
    }

    @PreAuthorize(ReportExportService.VIEW_JOB)
    @GetMapping("/reports/jobs/{id}")
    ReportExportJobView job(@PathVariable UUID id) {
        return service.job(id);
    }

    @PreAuthorize(ReportExportService.VIEW_JOB)
    @GetMapping("/reports/jobs/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable UUID id) throws IOException {
        ReportExportService.ReadyDownload ready = service.download(id);
        if (!Files.exists(ready.file())) throw new ApiException(ErrorCode.NOT_FOUND);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ready.format().contentType()))
                .header("Content-Disposition", "attachment; filename=\"" + ready.filename() + "\"")
                .body(Files.readAllBytes(ready.file()));
    }
}
