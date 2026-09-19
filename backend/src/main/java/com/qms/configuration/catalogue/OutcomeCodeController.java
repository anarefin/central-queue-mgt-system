package com.qms.configuration.catalogue;

import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Outcome codes are configured without a code change and are only ever deactivated, never deleted (FR-AGT-033). */
@RestController
@RequestMapping("/outcome-codes")
@Profile(Profiles.SERVING)
public class OutcomeCodeController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final CatalogueService catalogue;

    OutcomeCodeController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}")
    public OutcomeCode get(@PathVariable UUID id) {
        return catalogue.outcome(id);
    }

    @PreAuthorize(PERMISSION)
    @PatchMapping("/{id}")
    public OutcomeCode update(@PathVariable UUID id, @RequestBody UpdateOutcomeCodeRequest request) {
        return catalogue.updateOutcome(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/deactivate")
    public OutcomeCode deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return catalogue.deactivateOutcome(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/activate")
    public OutcomeCode activate(@PathVariable UUID id) {
        return catalogue.activateOutcome(id);
    }
}
