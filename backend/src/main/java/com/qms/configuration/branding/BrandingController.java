package com.qms.configuration.branding;

import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Branding and the printed-token template over HTTP (SRS §7.5, FR-CFG-030..032). Permissions are also enforced in
 * {@link BrandingService} (API-016).
 */
@RestController
@Profile(Profiles.SERVING)
public class BrandingController {

    private final BrandingService branding;
    private final CurrentUser currentUser;

    BrandingController(BrandingService branding, CurrentUser currentUser) {
        this.branding = branding;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)")
    @GetMapping("/branding")
    public OrgBranding branding() {
        return branding.branding();
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)")
    @PutMapping("/branding")
    public OrgBranding updateBranding(@RequestBody(required = false) BrandingRequest request) {
        return branding.updateBranding(currentUser.require().userId(), request);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)")
    @GetMapping("/print-template")
    public PrintTemplate template() {
        return branding.template();
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)")
    @PutMapping("/print-template")
    public PrintTemplate updateTemplate(@RequestBody(required = false) PrintTemplateRequest request) {
        return branding.updateTemplate(currentUser.require().userId(), request);
    }
}
