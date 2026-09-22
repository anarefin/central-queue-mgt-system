package com.qms.issuance.bundle;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export and import of the signed configuration bundle (CFG-004, ticket 55). {@link ConfigBundleService} repeats the
 * permission check (API-016) and requires an organisation-wide caller.
 */
@RestController
@Profile(Profiles.SERVING)
public class ConfigBundleController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final ConfigBundleService bundles;

    ConfigBundleController(ConfigBundleService bundles) {
        this.bundles = bundles;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/config/bundle")
    public ConfigBundleResponse export() {
        return bundles.export();
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/config/bundle/import")
    public ConfigBundleImportResult importBundle(@RequestBody(required = false) ConfigBundleImportRequest request) {
        return bundles.apply(request);
    }
}
