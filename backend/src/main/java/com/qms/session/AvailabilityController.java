package com.qms.session;

import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent availability for Team and Org Admins (FR-AGT-024, SRS §5.2 "Force-set agent availability"). Each method carries its
 * permission here, where the build-time check looks for it (FR-CFG-108), and the service checks it again along with scope.
 */
@RestController
@Profile(Profiles.SERVING)
public class AvailabilityController {

    private final SessionService service;

    AvailabilityController(SessionService service) {
        this.service = service;
    }

    @PreAuthorize(SessionService.FORCE_SET)
    @GetMapping("/agents/availability")
    public AvailabilityView.Items availability() {
        return service.availability();
    }

    @PreAuthorize(SessionService.FORCE_SET)
    @PutMapping("/agents/{agentId}/availability")
    public AvailabilityView setAvailability(@PathVariable UUID agentId, @RequestBody(required = false) AvailabilityRequest request) {
        return service.setAvailability(agentId, request);
    }
}
