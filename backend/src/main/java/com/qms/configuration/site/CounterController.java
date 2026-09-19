package com.qms.configuration.site;

import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/counters")
@Profile(Profiles.SERVING)
public class CounterController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final HierarchyService service;

    CounterController(HierarchyService service) {
        this.service = service;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}")
    public Counter get(@PathVariable UUID id) {
        return service.counter(id);
    }

    @PreAuthorize(PERMISSION)
    @PatchMapping("/{id}")
    public Counter update(@PathVariable UUID id, @RequestBody UpdateCounterRequest request) {
        return service.updateCounter(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/deactivate")
    public Counter deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return service.deactivateCounter(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/activate")
    public Counter activate(@PathVariable UUID id) {
        return service.activateCounter(id);
    }
}
