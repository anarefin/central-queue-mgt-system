package com.qms.integration.serviceaccount;

import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin provisioning of service accounts (ticket 58, FR-INT-030): create one (the client secret is shown back
 * exactly once), list and read them, and deactivate/reactivate a credential — the org admin's own way to revoke a
 * compromised or retired one without deleting its history. Each method carries its permission here as well as on
 * {@link ServiceAccountService}, so the build-time check that every endpoint is secured (FR-CFG-108) sees it.
 */
@RestController
@RequestMapping("/service-accounts")
@Profile(Profiles.SERVING)
public class ServiceAccountAdminController {

    private final ServiceAccountService service;
    private final CurrentUser currentUser;

    ServiceAccountAdminController(ServiceAccountService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PreAuthorize(ServiceAccountService.MANAGE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceAccountView create(@RequestBody(required = false) CreateServiceAccountRequest request) {
        String label = request == null ? null : request.label();
        Set<UUID> siteIds = request == null ? null : request.siteIds();
        return service.create(label, siteIds, currentUser.require().userId());
    }

    @PreAuthorize(ServiceAccountService.MANAGE)
    @GetMapping
    public List<ServiceAccountView> list() {
        return service.list();
    }

    @PreAuthorize(ServiceAccountService.MANAGE)
    @GetMapping("/{id}")
    public ServiceAccountView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize(ServiceAccountService.MANAGE)
    @PostMapping("/{id}/deactivate")
    public ServiceAccountView deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PreAuthorize(ServiceAccountService.MANAGE)
    @PostMapping("/{id}/activate")
    public ServiceAccountView activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}
