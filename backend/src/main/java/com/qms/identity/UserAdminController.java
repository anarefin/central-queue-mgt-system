package com.qms.identity;

import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff user administration for Org Admin and System Administrator. Each method carries its permission here as well as
 * on {@link UserAdminService}, so the build-time check that every endpoint is secured (FR-CFG-108) sees it.
 */
@RestController
@RequestMapping("/users")
@Profile(Profiles.SERVING)
public class UserAdminController {

    private final UserAdminService service;

    UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        List<RoleAssignment> roles = request.roles() == null ? List.of() : request.roles().stream().map(RoleAssignmentRequest::toAssignment).toList();
        return service.create(new CreateUserCommand(request.username(), request.password(), request.displayName(), request.preferredLanguage(), roles));
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @GetMapping
    public UserPage list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @PatchMapping("/{id}")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request.displayName(), request.preferredLanguage());
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).ROLE_ASSIGN)")
    @PutMapping("/{id}/roles")
    public UserView replaceRoles(@PathVariable UUID id, @Valid @RequestBody ReplaceRolesRequest request) {
        return service.replaceRoles(id, request.roles().stream().map(RoleAssignmentRequest::toAssignment).toList(), request.reason());
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @PostMapping("/{id}/disable")
    public UserView disable(@PathVariable UUID id, @Valid @RequestBody(required = false) DisableUserRequest request) {
        return service.disable(id, request == null ? null : request.reason());
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).USER_MANAGE)")
    @PostMapping("/{id}/enable")
    public UserView enable(@PathVariable UUID id) {
        return service.enable(id);
    }
}
