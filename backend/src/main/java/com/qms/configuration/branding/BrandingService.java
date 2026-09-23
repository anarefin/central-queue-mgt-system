package com.qms.configuration.branding;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation branding and the printed-token template (SRS §7.5, FR-CFG-030..032): the logo, primary colour and
 * organisation name applied everywhere, and the fixed-field template the printed token follows. Both are
 * organisation-wide singletons an Org Admin edits; every change writes an audit entry with before and after values
 * (FR-SEC-040), and a change that changes nothing writes nothing. Kiosk and display read the current values through
 * {@link #forDevice()} and {@link #templateForDevice()} as part of their own bootstrap (ticket 24), not through the
 * admin-only methods below, which need {@code config:org_sites_zones} a device principal never has.
 */
@Service
@Profile(Profiles.SERVING)
public class BrandingService {

    private static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    /** Sentinel entity ids for the audit log (same idiom as {@code issuance_settings}'s), since a singleton has no natural id. */
    static final UUID BRANDING_ID = new UUID(0, 1);
    static final UUID PRINT_TEMPLATE_ID = new UUID(0, 1);

    private final BrandingRepository brandingRepository;
    private final PrintTemplateRepository templateRepository;
    private final AuditWriter audit;
    private final Clock clock;

    BrandingService(BrandingRepository brandingRepository, PrintTemplateRepository templateRepository, AuditWriter audit, Clock clock) {
        this.brandingRepository = brandingRepository;
        this.templateRepository = templateRepository;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public OrgBranding branding() {
        return brandingRepository.get();
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public OrgBranding updateBranding(UUID actor, BrandingRequest request) {
        OrgBranding before = brandingRepository.get();
        OrgBranding after = new OrgBranding(
                BrandingRules.orgName(request == null ? null : request.orgName()),
                BrandingRules.primaryColor(request == null ? null : request.primaryColor()),
                BrandingRules.logoUrl(request == null ? null : request.logoUrl()),
                before.updatedAt(),
                before.updatedBy());
        if (snapshot(after).equals(snapshot(before))) return before;
        Instant now = clock.instant();
        brandingRepository.save(after, actor, now);
        OrgBranding saved = new OrgBranding(after.orgName(), after.primaryColor(), after.logoUrl(), now, actor);
        audit.record(AuditEvent.of("branding.updated", "org_branding", BRANDING_ID).withBefore(snapshot(before)).withAfter(snapshot(saved)));
        return saved;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public PrintTemplate template() {
        return templateRepository.get();
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public PrintTemplate updateTemplate(UUID actor, PrintTemplateRequest request) {
        PrintTemplate before = templateRepository.get();
        PrintTemplate after = new PrintTemplate(
                BrandingRules.fields(request == null ? null : request.fields()),
                BrandingRules.noticeLine(request == null ? null : request.noticeLine()),
                before.updatedAt(),
                before.updatedBy());
        if (snapshot(after).equals(snapshot(before))) return before;
        Instant now = clock.instant();
        templateRepository.save(after, actor, now);
        PrintTemplate saved = new PrintTemplate(after.fields(), after.noticeLine(), now, actor);
        audit.record(AuditEvent.of("print_template.updated", "print_template", PRINT_TEMPLATE_ID).withBefore(snapshot(before)).withAfter(snapshot(saved)));
        return saved;
    }

    // ---- public read (GET /branding/theme, ticket 62) -------------------------------------------------------------

    /**
     * The non-sensitive subset any caller may read (no token at all): a login screen or the anonymous visitor page
     * themes itself before there is a session to carry {@code config:org_sites_zones}. No {@link PreAuthorize} here,
     * the same convention as other unauthenticated reads (e.g. {@code VapidKeyStore}) — the controller's
     * {@code @PublicEndpoint} marker is what the security filter chain and {@code ControllerSecurityTest} act on.
     */
    @Transactional(readOnly = true)
    public BrandingTheme theme() {
        return BrandingTheme.from(brandingRepository.get());
    }

    // ---- device-facing (bootstrap, ticket 24) ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional(readOnly = true)
    public OrgBranding forDevice() {
        return brandingRepository.get();
    }

    @PreAuthorize("hasAnyRole('KIOSK','DISPLAY')")
    @Transactional(readOnly = true)
    public PrintTemplate templateForDevice() {
        return templateRepository.get();
    }

    private static Map<String, Object> snapshot(OrgBranding b) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("org_name", b.orgName());
        values.put("primary_color", b.primaryColor());
        values.put("logo_url", b.logoUrl());
        return values;
    }

    private static Map<String, Object> snapshot(PrintTemplate t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fields", t.fields());
        values.put("notice_line", t.noticeLine());
        return values;
    }
}
