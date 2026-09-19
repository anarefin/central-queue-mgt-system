package com.qms.configuration.site;

import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Zones and their counters. */
@RestController
@RequestMapping("/zones")
@Profile(Profiles.SERVING)
public class ZoneController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final HierarchyService service;

    ZoneController(HierarchyService service) {
        this.service = service;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}")
    public Zone get(@PathVariable UUID id) {
        return service.zone(id);
    }

    @PreAuthorize(PERMISSION)
    @PatchMapping("/{id}")
    public Zone update(@PathVariable UUID id, @RequestBody UpdateZoneRequest request) {
        return service.updateZone(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/deactivate")
    public Zone deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return service.deactivateZone(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/activate")
    public Zone activate(@PathVariable UUID id) {
        return service.activateZone(id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{zoneId}/counters")
    public Items<Counter> counters(@PathVariable UUID zoneId) {
        return new Items<>(service.counters(zoneId));
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{zoneId}/counters")
    @ResponseStatus(HttpStatus.CREATED)
    public Counter createCounter(@PathVariable UUID zoneId, @RequestBody CreateCounterRequest request) {
        return service.createCounter(zoneId, request.label(), request.locationNote());
    }
}
