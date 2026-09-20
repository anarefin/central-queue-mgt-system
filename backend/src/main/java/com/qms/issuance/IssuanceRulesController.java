package com.qms.issuance;

import com.qms.issuance.IssuanceRulesViews.Cutoffs;
import com.qms.issuance.IssuanceRulesViews.Holiday;
import com.qms.issuance.IssuanceRulesViews.HolidayRequest;
import com.qms.issuance.IssuanceRulesViews.Holidays;
import com.qms.issuance.IssuanceRulesViews.Hours;
import com.qms.platform.Profiles;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules that make issuance refuse (SRS §7.3, §8.1, §20.6, §26.5): hours, holidays and cut-offs of a Site, the hours,
 * cap, duplicate policy and roster rule of a Service, and maintenance mode with the rate limits. Each method carries its
 * permission here, where the build-time check looks for it (FR-CFG-108); {@link IssuanceRulesService} repeats it (API-016).
 */
@RestController
@Profile(Profiles.SERVING)
public class IssuanceRulesController {

    private final IssuanceRulesService rules;

    IssuanceRulesController(IssuanceRulesService rules) {
        this.rules = rules;
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @GetMapping("/sites/{siteId}/hours")
    public Hours siteHours(@PathVariable UUID siteId) {
        return rules.siteHours(siteId);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @PutMapping("/sites/{siteId}/hours")
    public Hours setSiteHours(@PathVariable UUID siteId, @RequestBody(required = false) Hours request) {
        return rules.setSiteHours(siteId, request);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @GetMapping("/sites/{siteId}/holidays")
    public Holidays holidays(@PathVariable UUID siteId) {
        return rules.holidays(siteId);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @PostMapping("/sites/{siteId}/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    public Holiday addHoliday(@PathVariable UUID siteId, @RequestBody(required = false) HolidayRequest request) {
        return rules.addHoliday(siteId, request);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @DeleteMapping("/sites/{siteId}/holidays/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeHoliday(@PathVariable UUID siteId, @PathVariable UUID id) {
        rules.removeHoliday(siteId, id);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @GetMapping("/sites/{siteId}/issuance-cutoffs")
    public Cutoffs cutoffs(@PathVariable UUID siteId) {
        return rules.cutoffs(siteId);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @PutMapping("/sites/{siteId}/issuance-cutoffs")
    public Cutoffs setCutoffs(@PathVariable UUID siteId, @RequestBody(required = false) Cutoffs request) {
        return rules.setCutoffs(siteId, request);
    }

    @PreAuthorize(IssuanceRulesService.CATALOGUE)
    @GetMapping("/services/{id}/hours")
    public Hours serviceHours(@PathVariable UUID id) {
        return rules.serviceHours(id);
    }

    @PreAuthorize(IssuanceRulesService.CATALOGUE)
    @PutMapping("/services/{id}/hours")
    public Hours setServiceHours(@PathVariable UUID id, @RequestBody(required = false) Hours request) {
        return rules.setServiceHours(id, request);
    }

    @PreAuthorize(IssuanceRulesService.CATALOGUE)
    @GetMapping("/services/{id}/issuance-rule")
    public IssuanceRulesViews.ServiceRule serviceRule(@PathVariable UUID id) {
        return rules.serviceRule(id);
    }

    @PreAuthorize(IssuanceRulesService.CATALOGUE)
    @PutMapping("/services/{id}/issuance-rule")
    public IssuanceRulesViews.ServiceRule setServiceRule(@PathVariable UUID id, @RequestBody(required = false) IssuanceRulesViews.ServiceRule request) {
        return rules.setServiceRule(id, request);
    }

    @PreAuthorize(IssuanceRulesService.CATALOGUE)
    @GetMapping("/services/{id}/remote-rule")
    public IssuanceRulesViews.RemoteRule remoteRule(@PathVariable UUID id) {
        return rules.remoteRule(id);
    }

    @PreAuthorize(IssuanceRulesService.CATALOGUE)
    @PutMapping("/services/{id}/remote-rule")
    public IssuanceRulesViews.RemoteRule setRemoteRule(@PathVariable UUID id, @RequestBody(required = false) IssuanceRulesViews.RemoteRule request) {
        return rules.setRemoteRule(id, request);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @GetMapping("/sites/{siteId}/location")
    public IssuanceRulesViews.SiteLocation siteLocation(@PathVariable UUID siteId) {
        return rules.siteLocation(siteId);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @PutMapping("/sites/{siteId}/location")
    public IssuanceRulesViews.SiteLocation setSiteLocation(@PathVariable UUID siteId, @RequestBody(required = false) IssuanceRulesViews.SiteLocation request) {
        return rules.setSiteLocation(siteId, request);
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @GetMapping("/issuance-settings")
    public IssuanceRulesViews.Settings settings() {
        return rules.settings();
    }

    @PreAuthorize(IssuanceRulesService.SITES)
    @PutMapping("/issuance-settings")
    public IssuanceRulesViews.Settings setSettings(@RequestBody(required = false) IssuanceRulesViews.Settings request) {
        return rules.setSettings(request);
    }
}
