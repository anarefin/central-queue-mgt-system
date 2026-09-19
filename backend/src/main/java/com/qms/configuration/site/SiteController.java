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

/** Sites and their zones. Each method carries its permission here too, so the build-time check sees it (FR-CFG-108). */
@RestController
@RequestMapping("/sites")
@Profile(Profiles.SERVING)
public class SiteController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final HierarchyService service;

    SiteController(HierarchyService service) {
        this.service = service;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping
    public Items<Site> list() {
        return new Items<>(service.sites());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Site create(@RequestBody CreateSiteRequest request) {
        return service.createSite(
                request.name(), request.code(), request.timezone(), request.address(), request.defaultLanguage(), request.enabledLanguages());
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}")
    public Site get(@PathVariable UUID id) {
        return service.site(id);
    }

    @PreAuthorize(PERMISSION)
    @PatchMapping("/{id}")
    public Site update(@PathVariable UUID id, @RequestBody UpdateSiteRequest request) {
        return service.updateSite(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/deactivate")
    public Site deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return service.deactivateSite(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/activate")
    public Site activate(@PathVariable UUID id) {
        return service.activateSite(id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{siteId}/zones")
    public Items<Zone> zones(@PathVariable UUID siteId) {
        return new Items<>(service.zones(siteId));
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{siteId}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public Zone createZone(@PathVariable UUID siteId, @RequestBody CreateZoneRequest request) {
        return service.createZone(siteId, request.name(), request.floorLabel(), request.buildingLabel(), request.displayOrder());
    }
}
