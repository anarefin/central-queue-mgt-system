package com.qms.issuance.setup;

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

    private final VerticalProfileService profileService;
    private final SetupWizardService wizard;
    private final CurrentUser currentUser;

    SetupController(VerticalProfileService profileService, SetupWizardService wizard, CurrentUser currentUser) {
        this.profileService = profileService;
        this.wizard = wizard;
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
    @PostMapping("/setup/go-live")
    public Map<String, Instant> goLive() {
        return Map.of("go_live_at", wizard.goLive(currentUser.require().userId()));
    }

    private static VerticalProfileId profileId(ProfileApplyRequest request) {
        if (request == null || request.profileId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "profile_id", "code", "required"))));
        }
        return VerticalProfileId.tryFromWire(request.profileId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", "profile_id", "code", "not_found")))));
    }
}
