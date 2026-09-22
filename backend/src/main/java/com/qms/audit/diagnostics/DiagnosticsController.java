package com.qms.audit.diagnostics;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /ops/diagnostics}: the one-click support bundle (FR-OPS-040, §26.5), System Administrator only. */
@RestController
@RequestMapping("/ops")
class DiagnosticsController {

    private static final MediaType ZIP = new MediaType("application", "zip");

    private final DiagnosticsService service;

    DiagnosticsController(DiagnosticsService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).OPS_DIAGNOSTICS_EXPORT)")
    @GetMapping("/diagnostics")
    ResponseEntity<byte[]> diagnostics() {
        byte[] bundle = service.build();
        return ResponseEntity.ok()
                .contentType(ZIP)
                .header("Content-Disposition", "attachment; filename=\"" + service.filename() + "\"")
                .body(bundle);
    }
}
