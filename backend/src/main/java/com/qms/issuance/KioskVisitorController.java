package com.qms.issuance;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The kiosk's own, minimal window onto the visitor directory (ticket 26, FR-ISS-013, FR-ISS-014): a paired kiosk
 * device, not staff, is the caller, so unlike {@link VisitorController}'s Reception-facing lookup this never returns
 * a phone number, external code or flag. Authorised the same way as {@link KioskTicketController}: {@code
 * hasRole('KIOSK')}, not a human permission.
 */
@RestController
@Profile(Profiles.SERVING)
public class KioskVisitorController {

    private static final String IDENTIFY = "hasRole('KIOSK')";

    private final VisitorService visitors;

    KioskVisitorController(VisitorService visitors) {
        this.visitors = visitors;
    }

    @PreAuthorize(IDENTIFY)
    @GetMapping("/kiosk/visitors/identify")
    public KioskVisitorIdentifyResponse identify(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) throw invalid("q", "required");
        return visitors.identifyForKiosk(q.strip());
    }

    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", field, "code", code))));
    }
}
