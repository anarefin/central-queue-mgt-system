package com.qms.issuance;

import com.qms.platform.Profiles;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token numbering rules per Service or Service group and the preview of the next Token number (FR-CFG-018). All of it
 * needs {@code config:service_catalogue}; the checks are repeated in {@link NumberingService} (API-016).
 */
@RestController
@Profile(Profiles.SERVING)
public class NumberingController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    /** A bounded list response: a site has few rules. */
    public record Rules(List<NumberingRuleView> items) {}

    private final NumberingService numbering;

    NumberingController(NumberingService numbering) {
        this.numbering = numbering;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/sites/{siteId}/numbering-rules")
    public Rules rules(@PathVariable UUID siteId) {
        return new Rules(numbering.rules(siteId));
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/services/{id}/numbering-rule")
    public NumberingRuleView serviceRule(@PathVariable UUID id) {
        return numbering.rule(NumberingRule.SERVICE, id);
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/services/{id}/numbering-rule")
    public NumberingRuleChange setServiceRule(@PathVariable UUID id, @RequestBody(required = false) NumberingRuleRequest request) {
        return numbering.setRule(NumberingRule.SERVICE, id, request);
    }

    @PreAuthorize(PERMISSION)
    @DeleteMapping("/services/{id}/numbering-rule")
    public NumberingRuleChange removeServiceRule(@PathVariable UUID id) {
        return numbering.removeRule(NumberingRule.SERVICE, id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/services/{id}/numbering-preview")
    public NumberingPreview servicePreview(@PathVariable UUID id) {
        return numbering.preview(NumberingRule.SERVICE, id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}/numbering-rule")
    public NumberingRuleView groupRule(@PathVariable UUID id) {
        return numbering.rule(NumberingRule.SERVICE_GROUP, id);
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/service-groups/{id}/numbering-rule")
    public NumberingRuleChange setGroupRule(@PathVariable UUID id, @RequestBody(required = false) NumberingRuleRequest request) {
        return numbering.setRule(NumberingRule.SERVICE_GROUP, id, request);
    }

    @PreAuthorize(PERMISSION)
    @DeleteMapping("/service-groups/{id}/numbering-rule")
    public NumberingRuleChange removeGroupRule(@PathVariable UUID id) {
        return numbering.removeRule(NumberingRule.SERVICE_GROUP, id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}/numbering-preview")
    public NumberingPreview groupPreview(@PathVariable UUID id) {
        return numbering.preview(NumberingRule.SERVICE_GROUP, id);
    }
}
