package com.qms.issuance.setup;

import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Terminology remapping over HTTP (SRS §3.2): every visitor-facing noun a screen shows is a label key ({@code
 * entity.visitor}, {@code entity.ticket}, ...) resolved through the active vertical profile, then editable one key
 * at a time afterwards (CFG-003). Reading labels needs no configuration permission: every staff and device
 * principal renders screens that use them.
 */
@RestController
@Profile(Profiles.SERVING)
public class LabelController {

    private static final String WRITE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final LabelOverrideRepository labels;
    private final CurrentUser currentUser;
    private final Clock clock;

    LabelController(LabelOverrideRepository labels, CurrentUser currentUser, Clock clock) {
        this.labels = labels;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/labels")
    public Map<String, String> labels(@RequestParam(defaultValue = "en") String lang) {
        return labels.forLanguage(lang);
    }

    @PreAuthorize(WRITE)
    @PutMapping("/labels/{key}")
    public Map<String, String> update(@PathVariable String key, @RequestBody LabelUpdateRequest request) {
        labels.upsert(key, request.lang(), request.value(), currentUser.require().userId(), clock.instant());
        return labels.forLanguage(request.lang());
    }
}
