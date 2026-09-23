package com.qms.issuance.setup;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.devices.DeviceConfigNotifier;
import com.qms.platform.featureflags.FeatureFlagKey;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>Reading needs no configuration permission (ticket 68): every staff and device principal that renders a screen
 * gated by a flag ({@code GET /config/bootstrap}, for devices) needs to know its state, the same reach {@link
 * LabelController#labels} already gives label overrides. Writing stays admin-only, and is audited as {@code
 * feature_flag.updated} and pushed to every device as a {@code config.changed} so a live kiosk/display refetches
 * rather than waiting out its next heartbeat.
 */
@RestController
@Profile(Profiles.SERVING)
public class FeatureFlagController {

    private static final String WRITE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final FeatureFlagRepository flags;
    private final FeatureFlagsService cache;
    private final AuditWriter audit;
    private final Optional<DeviceConfigNotifier> deviceNotifier;
    private final CurrentUser currentUser;
    private final Clock clock;

    FeatureFlagController(
            FeatureFlagRepository flags,
            FeatureFlagsService cache,
            AuditWriter audit,
            Optional<DeviceConfigNotifier> deviceNotifier,
            CurrentUser currentUser,
            Clock clock) {
        this.flags = flags;
        this.cache = cache;
        this.audit = audit;
        this.deviceNotifier = deviceNotifier;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/setup/feature-flags")
    public Map<String, Boolean> flags() {
        return flags.all();
    }

    @PreAuthorize(WRITE)
    @PutMapping("/setup/feature-flags/{key}")
    public Map<String, Boolean> update(@PathVariable String key, @RequestBody FeatureFlagUpdateRequest request) {
        FeatureFlagKey.tryFromWire(key)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "key", "code", "not_found")))));
        Map<String, Boolean> before = flags.all();
        boolean was = before.getOrDefault(key, true);
        flags.upsert(key, request.enabled(), currentUser.require().userId(), clock.instant());
        cache.invalidate();
        audit.record(AuditEvent.of("feature_flag.updated", "feature_flag", null)
                .withBefore(Map.of("key", key, "enabled", was))
                .withAfter(Map.of("key", key, "enabled", request.enabled())));
        deviceNotifier.ifPresent(DeviceConfigNotifier::notifyEverySite);
        return flags.all();
    }
}
