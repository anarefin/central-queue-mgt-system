package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Visitor master-data CSV import over HTTP (SRS §22.2, FR-INT-010, FR-INT-011): the admin-set column mapping, a
 * manual upload that runs it immediately, and the run history a scheduled folder pickup ({@link
 * VisitorImportScheduler}) also writes to, so its own report is visible after the fact. §5.2 has no permission row of
 * its own for CSV import; {@code visitor_pii:view} is the closest fit already in the closed set — the same
 * permission the visitor directory's own search needs (ticket 22) — and it already excludes an Agent, who only ever
 * holds the directory's "own records" scope, never the plain authority a bulk import needs.
 */
@RestController
@Profile(Profiles.SERVING)
class VisitorImportController {

    private static final String IMPORT_MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).VISITOR_PII_VIEW)";

    private final VisitorImportService imports;
    private final CurrentUser currentUser;

    VisitorImportController(VisitorImportService imports, CurrentUser currentUser) {
        this.imports = imports;
        this.currentUser = currentUser;
    }

    @PreAuthorize(IMPORT_MANAGE)
    @GetMapping("/visitors/import/mapping")
    public VisitorImportMapping mapping() {
        return imports.mapping();
    }

    @PreAuthorize(IMPORT_MANAGE)
    @PutMapping("/visitors/import/mapping")
    public VisitorImportMapping updateMapping(@RequestBody(required = false) VisitorImportMapping request) {
        return imports.updateMapping(request, currentUser.require().userId());
    }

    @PreAuthorize(IMPORT_MANAGE)
    @PostMapping("/visitors/import")
    public ResponseEntity<VisitorImportReport> upload(@RequestBody(required = false) VisitorImportUploadRequest request) {
        if (request == null || blank(request.content()) == null) throw invalid("content", "required");
        VisitorImportReport report = imports.importCsv(request.filename(), request.content(), "manual", currentUser.require().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(report);
    }

    @PreAuthorize(IMPORT_MANAGE)
    @GetMapping("/visitors/import/runs")
    public Items<VisitorImportReport> runs() {
        return new Items<>(imports.recentRuns());
    }

    @PreAuthorize(IMPORT_MANAGE)
    @GetMapping("/visitors/import/runs/{id}")
    public VisitorImportReport run(@PathVariable UUID id) {
        return imports.run(id);
    }

    private static String blank(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
