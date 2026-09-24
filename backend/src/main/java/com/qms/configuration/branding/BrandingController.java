package com.qms.configuration.branding;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import com.qms.platform.security.PublicEndpoint;
import com.qms.platform.security.PublicReadRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
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

    /** 30 requests/minute/IP: generous for a page load's own theme fetch, tight enough to blunt a scraper (ticket 62). */
    private static final int THEME_RATE_LIMIT = 30;
    private static final long THEME_RATE_WINDOW_SECONDS = 60;

    private final BrandingService branding;
    private final CurrentUser currentUser;
    private final PublicReadRateLimiter rateLimiter;

    BrandingController(BrandingService branding, CurrentUser currentUser, PublicReadRateLimiter rateLimiter) {
        this.branding = branding;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)")
    @GetMapping("/branding")
    public OrgBranding branding() {
        return branding.branding();
    }

    @PublicEndpoint("A login screen or the anonymous visitor page themes itself before there is any session (FR-CFG-030)")
    @GetMapping("/branding/theme")
    public BrandingTheme theme(HttpServletRequest request) {
        String key = "branding-theme:" + request.getRemoteAddr();
        if (!rateLimiter.allow(key, THEME_RATE_LIMIT, THEME_RATE_WINDOW_SECONDS)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, java.util.Map.of("retry_after_seconds", 60, "limit", 30, "window_seconds", 60));
        }
        return branding.theme();
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
