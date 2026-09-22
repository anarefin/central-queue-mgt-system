package com.qms.issuance.setup;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feature flags over HTTP (CFG-001, CFG-003, SRS §3.3.6): the closed vocabulary a vertical profile turns on or off
 * at apply/reset time ({@link VerticalProfileService}), then editable one key at a time afterwards — the same
 * "seeded in bulk, then editable one key at a time" shape {@link LabelController} already gives label overrides.
 */
@RestController
@Profile(Profiles.SERVING)
public class FeatureFlagController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final FeatureFlagRepository flags;
    private final CurrentUser currentUser;
    private final Clock clock;

    FeatureFlagController(FeatureFlagRepository flags, CurrentUser currentUser, Clock clock) {
        this.flags = flags;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/setup/feature-flags")
    public Map<String, Boolean> flags() {
        return flags.all();
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/setup/feature-flags/{key}")
    public Map<String, Boolean> update(@PathVariable String key, @RequestBody FeatureFlagUpdateRequest request) {
        FeatureFlagKey.tryFromWire(key)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "key", "code", "not_found")))));
        flags.upsert(key, request.enabled(), currentUser.require().userId(), clock.instant());
        return flags.all();
    }
}
