package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The visitor directory over HTTP (SRS §20.4, §22.2). {@code GET /visitors/lookup} resolves a known visitor by code,
 * phone or QR (FR-ISS-020); {@code POST /visitors} registers an unknown walk-in (FR-ISS-021). Both are Reception
 * actions; neither is site-scoped, since the directory is not a per-site record.
 */
@RestController
@Profile(Profiles.SERVING)
class VisitorController {

    private static final String LOOKUP = "hasAuthority(T(com.qms.platform.security.Authorities).VISITOR_PII_VIEW)";
    private static final String REGISTER = "hasAuthority(T(com.qms.platform.security.Authorities).TICKET_ISSUE)";

    private final VisitorService visitors;

    VisitorController(VisitorService visitors) {
        this.visitors = visitors;
    }

    @PreAuthorize(LOOKUP)
    @GetMapping("/visitors/lookup")
    public VisitorLookupResponse lookup(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) throw invalid("q", "required");
        return visitors.lookup(q.strip());
    }

    @PreAuthorize(REGISTER)
    @PostMapping("/visitors")
    public ResponseEntity<VisitorRegistrationResponse> register(@RequestBody(required = false) RegisterVisitorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(visitors.register(request));
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
