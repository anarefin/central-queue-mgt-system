package com.qms.issuance;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** An Org Admin's own visitor data export and deletion (FR-SEC-031, ticket 54). */
@RestController
@Profile(Profiles.SERVING)
class VisitorPrivacyController {

    private final VisitorPrivacyService service;

    VisitorPrivacyController(VisitorPrivacyService service) {
        this.service = service;
    }

    @PreAuthorize(VisitorPrivacyService.MANAGE)
    @GetMapping("/visitors/{id}/export")
    public VisitorExportResponse export(@PathVariable UUID id) {
        return service.export(id);
    }

    @PreAuthorize(VisitorPrivacyService.MANAGE)
    @PostMapping("/visitors/{id}/anonymize")
    public VisitorAnonymizeResponse anonymize(@PathVariable UUID id) {
        return service.anonymize(id);
    }
}
