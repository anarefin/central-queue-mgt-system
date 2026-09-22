package com.qms.configuration.privacy;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /privacy/field-config} (FR-SEC-020, FR-SEC-023, ticket 54): which optional visitor fields the kiosk
 * confirmation screen shows and which are captured at all. Gated the same way {@code
 * configuration.branding.BrandingController} gates its own org-wide admin screen — {@code config:org_sites_zones},
 * since §5.2 has no dedicated permission for "manage privacy settings" and this is, at bottom, an org configuration
 * change (System/Org Admin only, matching this ticket's "An Org Admin controls...").
 */
@Service
@Profile(Profiles.SERVING)
public class VisitorFieldConfigService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";

    private final VisitorFieldConfigRepository repo;
    private final AuditWriter audit;
    private final CurrentUser currentUser;
    private final Clock clock;

    VisitorFieldConfigService(VisitorFieldConfigRepository repo, AuditWriter audit, CurrentUser currentUser, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    List<VisitorFieldConfigView> list(String surfaceWire) {
        VisitorFieldSurface surface = surface(surfaceWire);
        return repo.list(surface.wire()).stream().map(VisitorFieldConfigService::view).toList();
    }

    @PreAuthorize(MANAGE)
    @Transactional
    VisitorFieldConfigView update(String surfaceWire, String field, VisitorFieldConfigRequest request) {
        VisitorFieldSurface surface = surface(surfaceWire);
        if (!surface.fields().contains(field)) throw fail("field", "unknown_value");
        if (request == null || request.visible() == null) throw fail("visible", "required");

        VisitorFieldConfigRepository.Row before = repo.list(surface.wire()).stream()
                .filter(row -> row.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        AuthenticatedUser user = currentUser.require();
        Instant now = clock.instant();
        repo.setVisible(surface.wire(), field, request.visible(), user.userId(), now);

        audit.record(AuditEvent.of("privacy.field_config_changed", "visitor_field_config", null)
                .withBefore(Map.of("surface", surface.wire(), "field", field, "visible", before.visible()))
                .withAfter(Map.of("surface", surface.wire(), "field", field, "visible", request.visible())));

        return repo.list(surface.wire()).stream().filter(row -> row.field().equals(field)).findFirst().map(VisitorFieldConfigService::view).orElseThrow();
    }

    /**
     * Whether {@code field} of {@code surface} is currently on, by wire name — the one method the surfaces
     * themselves call (e.g. {@code issuance.VisitorService} for {@code "capture"}, {@code
     * issuance.KioskVisitorController} for {@code "kiosk_confirmation"}), unauthenticated on purpose: it is a
     * runtime read of configuration, not the admin screen {@link #list}/{@link #update} are.
     */
    public boolean isVisible(String surfaceWire, String field) {
        VisitorFieldSurface surface = VisitorFieldSurface.fromWire(surfaceWire).orElseThrow();
        return repo.isVisible(surface.wire(), field);
    }

    private static VisitorFieldSurface surface(String wire) {
        return VisitorFieldSurface.fromWire(wire).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static VisitorFieldConfigView view(VisitorFieldConfigRepository.Row row) {
        return new VisitorFieldConfigView(row.surface(), row.field(), row.visible(), row.updatedAt(), row.updatedBy());
    }

    private static ApiException fail(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
