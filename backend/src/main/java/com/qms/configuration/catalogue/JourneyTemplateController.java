package com.qms.configuration.catalogue;

import com.qms.configuration.site.Items;
import com.qms.platform.Profiles;
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

/** Journey templates over HTTP (ticket 31, FR-QUE-060): the Service catalogue admin's side of what Reception issues from. */
@RestController
@Profile(Profiles.SERVING)
public class JourneyTemplateController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final JourneyTemplateService templates;

    JourneyTemplateController(JourneyTemplateService templates) {
        this.templates = templates;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{groupId}/journey-templates")
    public Items<JourneyTemplate> list(@PathVariable UUID groupId) {
        return new Items<>(templates.ofGroup(groupId));
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/service-groups/{groupId}/journey-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyTemplate create(@PathVariable UUID groupId, @RequestBody CreateJourneyTemplateRequest request) {
        return templates.create(groupId, request);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/journey-templates/{id}")
    public JourneyTemplate get(@PathVariable UUID id) {
        return templates.get(id);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/journey-templates/{id}/deactivate")
    public JourneyTemplate deactivate(@PathVariable UUID id) {
        return templates.deactivate(id);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/journey-templates/{id}/activate")
    public JourneyTemplate activate(@PathVariable UUID id) {
        return templates.activate(id);
    }
}
