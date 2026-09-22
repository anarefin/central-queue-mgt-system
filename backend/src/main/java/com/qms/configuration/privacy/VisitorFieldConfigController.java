package com.qms.configuration.privacy;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The configurable visitor field set per surface (SRS §25.3, FR-SEC-020, FR-SEC-023, ticket 54). */
@RestController
@RequestMapping("/privacy/field-config")
@Profile(Profiles.SERVING)
class VisitorFieldConfigController {

    private final VisitorFieldConfigService service;

    VisitorFieldConfigController(VisitorFieldConfigService service) {
        this.service = service;
    }

    @PreAuthorize(VisitorFieldConfigService.MANAGE)
    @GetMapping("/{surface}")
    public Items<VisitorFieldConfigView> list(@PathVariable String surface) {
        return new Items<>(service.list(surface));
    }

    @PreAuthorize(VisitorFieldConfigService.MANAGE)
    @PutMapping("/{surface}/{field}")
    public VisitorFieldConfigView update(@PathVariable String surface, @PathVariable String field, @RequestBody VisitorFieldConfigRequest request) {
        return service.update(surface, field, request);
    }
}
