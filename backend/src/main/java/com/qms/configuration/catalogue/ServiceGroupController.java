package com.qms.configuration.catalogue;

import com.qms.configuration.site.Items;
import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service groups of a site, their services, counters and team. Permissions are also enforced in the services (API-016). */
@RestController
@Profile(Profiles.SERVING)
public class ServiceGroupController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";
    private static final String TEAM_CHANGE = "hasAuthority(T(com.qms.platform.security.Authorities).TEAM_MEMBER_APPROVE)";

    private final CatalogueService catalogue;
    private final TeamService teams;

    ServiceGroupController(CatalogueService catalogue, TeamService teams) {
        this.catalogue = catalogue;
        this.teams = teams;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/sites/{siteId}/service-groups")
    public Items<ServiceGroup> list(@PathVariable UUID siteId) {
        return new Items<>(catalogue.groups(siteId));
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/sites/{siteId}/service-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceGroup create(@PathVariable UUID siteId, @RequestBody CreateServiceGroupRequest request) {
        return catalogue.createGroup(siteId, request);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}")
    public ServiceGroup get(@PathVariable UUID id) {
        return catalogue.group(id);
    }

    @PreAuthorize(PERMISSION)
    @PatchMapping("/service-groups/{id}")
    public ServiceGroup update(@PathVariable UUID id, @RequestBody UpdateServiceGroupRequest request) {
        return catalogue.updateGroup(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/service-groups/{id}/deactivate")
    public ServiceGroup deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return catalogue.deactivateGroup(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/service-groups/{id}/activate")
    public ServiceGroup activate(@PathVariable UUID id) {
        return catalogue.activateGroup(id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}/services")
    public Items<ServiceEntry> services(@PathVariable UUID id) {
        return new Items<>(catalogue.services(id));
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/service-groups/{id}/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceEntry createService(@PathVariable UUID id, @RequestBody CreateServiceRequest request) {
        return catalogue.createService(id, request);
    }

    /** Active counters of the group's site that its services can be linked to. */
    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}/counters")
    public Items<CounterOption> counters(@PathVariable UUID id) {
        return new Items<>(catalogue.counterOptions(id));
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}/team")
    public Team team(@PathVariable UUID id) {
        return teams.team(id);
    }

    /** A direct change by an Org Admin. A Team Admin asks through {@code POST /approvals} instead (FR-CFG-102). */
    @PreAuthorize(TEAM_CHANGE)
    @PostMapping("/service-groups/{id}/team/members")
    public Team addMember(@PathVariable UUID id, @RequestBody AddMemberRequest request) {
        if (request.userId() == null) throw CatalogueRules.invalid("user_id", "NotNull");
        return teams.addMember(id, request.userId());
    }

    @PreAuthorize(TEAM_CHANGE)
    @DeleteMapping("/service-groups/{id}/team/members/{userId}")
    public Team removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        return teams.removeMember(id, userId);
    }
}
