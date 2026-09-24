package com.qms.issuance.setup;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.devices.DeviceConfigNotifier;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.PublicEndpoint;
import com.qms.platform.security.PublicReadRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * principal renders screens that use them; the anonymous visitor ticket/join pages read the seven {@code entity.*}
 * values only through {@link #publicLabels}, never the full map. A write pushes {@code config.changed} to every
 * device (ticket 69) the same way {@link FeatureFlagController#update} already does, and is audited.
 */
@RestController
@Profile(Profiles.SERVING)
public class LabelController {

    private static final String WRITE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    /** 30 requests/minute/IP: the same generous-but-bounded allowance {@code BrandingController#theme} gives its
     * own public, pre-session read (ticket 62). */
    private static final int PUBLIC_RATE_LIMIT = 30;
    private static final long PUBLIC_RATE_WINDOW_SECONDS = 60;

    private final LabelOverrideRepository labels;
    private final AuditWriter audit;
    private final Optional<DeviceConfigNotifier> deviceNotifier;
    private final PublicReadRateLimiter rateLimiter;
    private final CurrentUser currentUser;
    private final Clock clock;

    LabelController(
            LabelOverrideRepository labels,
            AuditWriter audit,
            Optional<DeviceConfigNotifier> deviceNotifier,
            PublicReadRateLimiter rateLimiter,
            CurrentUser currentUser,
            Clock clock) {
        this.labels = labels;
        this.audit = audit;
        this.deviceNotifier = deviceNotifier;
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/labels")
    public Map<String, String> labels(@RequestParam(defaultValue = "en") String lang) {
        return labels.forLanguage(lang);
    }

    /** The seven non-sensitive {@code entity.*} values only, for a screen rendered before there is any session
     * (the anonymous visitor ticket page and join page, ticket 69). */
    @PublicEndpoint("Anonymous visitor ticket/join pages render entity terminology before there is any session (SRS §3.2, ticket 69)")
    @GetMapping("/labels/public")
    public Map<String, String> publicLabels(@RequestParam(defaultValue = "en") String lang, HttpServletRequest request) {
        String key = "labels-public:" + request.getRemoteAddr();
        if (!rateLimiter.allow(key, PUBLIC_RATE_LIMIT, PUBLIC_RATE_WINDOW_SECONDS)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, Map.of("retry_after_seconds", 60, "limit", 30, "window_seconds", 60));
        }
        return labels.publicLabelsForLanguage(lang);
    }

    @PreAuthorize(WRITE)
    @PutMapping("/labels/{key}")
    public Map<String, String> update(@PathVariable String key, @RequestBody LabelUpdateRequest request) {
        String value = LabelRules.value(request.value());
        Map<String, String> before = labels.forLanguage(request.lang());
        labels.upsert(key, request.lang(), value, currentUser.require().userId(), clock.instant());
        Map<String, String> after = labels.forLanguage(request.lang());
        audit.record(AuditEvent.of("label.updated", "label", null)
                .withBefore(Map.of("key", key, "lang", request.lang(), "value", before.getOrDefault(key, "")))
                .withAfter(Map.of("key", key, "lang", request.lang(), "value", value)));
        deviceNotifier.ifPresent(DeviceConfigNotifier::notifyEverySite);
        return after;
    }

    /** Resets one key back to the pack's own default noun (CFG-003, ticket 69): the value a profile or an earlier
     * edit left behind for {@code lang} is discarded, not replaced with another value. */
    @PreAuthorize(WRITE)
    @DeleteMapping("/labels/{key}")
    public Map<String, String> reset(@PathVariable String key, @RequestParam String lang) {
        Map<String, String> before = labels.forLanguage(lang);
        labels.reset(key, lang);
        audit.record(AuditEvent.of("label.reset", "label", null)
                .withBefore(Map.of("key", key, "lang", lang, "value", before.getOrDefault(key, "")))
                .withAfter(Map.of("key", key, "lang", lang)));
        deviceNotifier.ifPresent(DeviceConfigNotifier::notifyEverySite);
        return labels.forLanguage(lang);
    }
}
