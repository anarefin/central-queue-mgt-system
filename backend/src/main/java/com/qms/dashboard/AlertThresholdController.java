package com.qms.dashboard;

import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A Service's own alert thresholds (SRS §15.4, FR-MON-020, ticket 47). */
@RestController
@Profile(Profiles.SERVING)
class AlertThresholdController {

    private final AlertThresholdService thresholds;
    private final CurrentUser currentUser;

    AlertThresholdController(AlertThresholdService thresholds, CurrentUser currentUser) {
        this.thresholds = thresholds;
        this.currentUser = currentUser;
    }

    @PreAuthorize(AlertThresholdService.MANAGE)
    @GetMapping("/services/{id}/alert-thresholds")
    AlertThreshold get(@PathVariable UUID id) {
        return thresholds.get(id);
    }

    @PreAuthorize(AlertThresholdService.MANAGE)
    @PutMapping("/services/{id}/alert-thresholds")
    AlertThreshold set(@PathVariable UUID id, @RequestBody AlertThresholdRequest request) {
        return thresholds.set(id, request, currentUser.require().userId());
    }
}
