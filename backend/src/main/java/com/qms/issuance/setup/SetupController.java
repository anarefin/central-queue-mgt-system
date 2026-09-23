package com.qms.issuance.setup;

import com.qms.configuration.catalogue.CatalogueSeeding;
import com.qms.issuance.TicketResponse;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first-run setup wizard over HTTP (SRS §26.2, FR-OPS-010, ticket 56). Each method carries its own permission,
 * where the build-time check looks for it (FR-CFG-108); every permission here is re-checked at the service layer
 * too (API-016).
 */
@RestController
@Profile(Profiles.SERVING)
public class SetupController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    private static final String CATALOGUE_SEED_PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)"
            + " and hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final VerticalProfileService profileService;
    private final SetupWizardService wizard;
    private final CatalogueSeedingService catalogueSeeding;
    private final CurrentUser currentUser;

    SetupController(VerticalProfileService profileService, SetupWizardService wizard, CatalogueSeedingService catalogueSeeding, CurrentUser currentUser) {
        this.profileService = profileService;
        this.wizard = wizard;
        this.catalogueSeeding = catalogueSeeding;
        this.currentUser = currentUser;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/setup/profiles")
    public List<VerticalProfileDefinition> profiles() {
        return profileService.list();
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/setup/profile")
    public ActiveProfile applyProfile(@RequestBody ProfileApplyRequest request) {
        return profileService.apply(profileId(request), currentUser.require().userId());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/setup/profile/reset")
    public ActiveProfile resetProfile(@RequestBody ProfileApplyRequest request) {
        return profileService.reset(profileId(request), currentUser.require().userId());
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/setup/state")
    public SetupState state() {
        return wizard.state();
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/setup/test-token")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse issueTestToken(@RequestBody TestTokenRequest request) {
        return wizard.issueTestToken(request.serviceId(), currentUser.require().userId());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/setup/test-token/{ticketId}/confirm-print")
    public SetupState confirmPrint(@PathVariable UUID ticketId) {
        wizard.confirmPrinted(ticketId, currentUser.require().userId());
        return wizard.state();
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/setup/test-token/{ticketId}/confirm-announce")
    public SetupState confirmAnnounce(@PathVariable UUID ticketId) {
        wizard.confirmAnnounced(ticketId, currentUser.require().userId());
        return wizard.state();
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/setup/go-live")
    public Map<String, Instant> goLive() {
        return Map.of("go_live_at", wizard.goLive(currentUser.require().userId()));
    }

    /** Seeds one Site's starter catalogue and numbering from the active vertical profile (ticket 67): needs both
     * {@code config:org_sites_zones} and {@code config:service_catalogue}, re-checked at the service layer (API-016). */
    @PreAuthorize(CATALOGUE_SEED_PERMISSION)
    @PostMapping("/setup/seed-catalogue")
    public CatalogueSeeding.SeedResult seedCatalogue(@RequestBody SeedCatalogueRequest request) {
        if (request == null || request.siteId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "site_id", "code", "required"))));
        }
        return catalogueSeeding.seedStarter(request.siteId());
    }

    private static VerticalProfileId profileId(ProfileApplyRequest request) {
        if (request == null || request.profileId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "profile_id", "code", "required"))));
        }
        return VerticalProfileId.tryFromWire(request.profileId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "profile_id", "code", "not_found")))));
    }
}
