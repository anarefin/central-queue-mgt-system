package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.idempotency.IdempotencyService;
import com.qms.platform.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journeys over HTTP (ticket 31, FR-ISS-022). {@code POST /journeys} is Reception's one action that issues a
 * multi-stop Journey's stops, every one of them sharing a Visit; like {@code POST /tickets} it insists on an
 * {@code Idempotency-Key}. The Journey feature flag and the templates offered at a Site are read and, for the flag,
 * written here too.
 */
@RestController
@Profile(Profiles.SERVING)
public class JourneyController {

    private static final String ISSUE = "hasAuthority(T(com.qms.platform.security.Authorities).TICKET_ISSUE)";
    /** Journeys are a Service catalogue concern (which Services, which order); the same permission configures templates. */
    private static final String CONFIGURE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final JourneyService journeys;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;

    JourneyController(JourneyService journeys, IdempotencyService idempotency, CurrentUser currentUser) {
        this.journeys = journeys;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
    }

    /** Issues a Journey. Replaying the key within 24 hours returns the original Journey and issues nothing again. */
    @PreAuthorize(ISSUE)
    @PostMapping("/journeys")
    public ResponseEntity<JourneyResponse> issue(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey, @RequestBody(required = false) IssueJourneyRequest request) {
        IdempotencyService.requireUsable(idempotencyKey);
        if (request == null || (request.journeyTemplateId() == null && (request.serviceIds() == null || request.serviceIds().isEmpty()))) {
            throw invalid("service_ids", "required");
        }
        UUID actor = currentUser.require().userId();
        var result = idempotency.execute("POST /journeys:" + actor, idempotencyKey, fingerprint(request), JourneyResponse.class, () -> journeys.issue(request, actor));
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(result.value());
    }

    @PreAuthorize(ISSUE)
    @GetMapping("/sites/{siteId}/journey-templates")
    public List<JourneyTemplateSummaryResponse> templates(@PathVariable UUID siteId) {
        return journeys.templatesForSite(siteId).stream().map(JourneyTemplateSummaryResponse::of).toList();
    }

    @PreAuthorize(CONFIGURE)
    @GetMapping("/journey-settings")
    public JourneySettingsView settings() {
        return new JourneySettingsView(journeys.settingsEnabled());
    }

    @PreAuthorize(CONFIGURE)
    @PutMapping("/journey-settings")
    public JourneySettingsView updateSettings(@RequestBody JourneySettingsView request) {
        if (request == null) throw invalid("enabled", "required");
        return new JourneySettingsView(journeys.settingsEnabled(request.enabled()));
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }

    private static String fingerprint(IssueJourneyRequest request) {
        try {
            StringBuilder basis = new StringBuilder();
            if (request.journeyTemplateId() != null) basis.append("template:").append(request.journeyTemplateId());
            if (request.serviceIds() != null) basis.append("|services:").append(request.serviceIds());
            basis.append("|ordered:").append(request.ordered());
            if (request.priorityClassId() != null) basis.append("|class:").append(request.priorityClassId());
            if (request.visitorId() != null) basis.append("|visitor:").append(request.visitorId());
            if (request.purposeNote() != null && !request.purposeNote().isBlank()) basis.append("|note:").append(request.purposeNote());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(basis.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
