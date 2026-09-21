package com.qms.reporting;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Retention policy over HTTP (ticket 53, FR-SEC-032): {@code /retention/policies} lists and updates how long each
 * data class is kept. */
@RestController
@Profile(Profiles.SERVING)
class RetentionPolicyController {

    private final RetentionPolicyService service;

    RetentionPolicyController(RetentionPolicyService service) {
        this.service = service;
    }

    @PreAuthorize(RetentionPolicyService.MANAGE)
    @GetMapping("/retention/policies")
    Items<RetentionPolicyView> list() {
        return new Items<>(service.list());
    }

    @PreAuthorize(RetentionPolicyService.MANAGE)
    @PutMapping("/retention/policies/{dataClass}")
    RetentionPolicyView update(@PathVariable String dataClass, @RequestBody RetentionPolicyRequest request) {
        return service.update(dataClass, request);
    }
}
