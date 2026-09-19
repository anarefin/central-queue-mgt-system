package com.qms.configuration.priority;

import com.qms.configuration.site.Items;
import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Priority classes and the ordering strategy of Service groups (FR-QUE-010, FR-QUE-021). Permissions are also
 * enforced in {@link PriorityService} (API-016).
 */
@RestController
@Profile(Profiles.SERVING)
public class PriorityController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_PRIORITY_ROUTING)";
    /** Reception reads the classes to offer them when it issues a ticket. */
    private static final String READ = "hasAnyAuthority(T(com.qms.platform.security.Authorities).CONFIG_PRIORITY_ROUTING,"
            + " T(com.qms.platform.security.Authorities).TICKET_ISSUE)";

    /** The reason a class is switched off, kept in the audit log. */
    public record DeactivateRequest(@jakarta.validation.constraints.Size(max = 500) String reason) {}

    private final PriorityService priority;

    PriorityController(PriorityService priority) {
        this.priority = priority;
    }

    @PreAuthorize(READ)
    @GetMapping("/priority-classes")
    public Items<PriorityClass> classes() {
        return new Items<>(priority.classes());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/priority-classes")
    @ResponseStatus(HttpStatus.CREATED)
    public PriorityClass create(@RequestBody PriorityClassRequest request) {
        return priority.create(request);
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/priority-classes/{id}")
    public PriorityClass replace(@PathVariable UUID id, @RequestBody PriorityClassRequest request) {
        return priority.replace(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/priority-classes/{id}/deactivate")
    public PriorityClass deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return priority.deactivate(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/priority-classes/{id}/activate")
    public PriorityClass activate(@PathVariable UUID id) {
        return priority.activate(id);
    }

    /** The classes tickets get by default per channel and per Service when staff choose none (FR-QUE-011). */
    @PreAuthorize(PERMISSION)
    @GetMapping("/priority-defaults")
    public PriorityDefaults defaults() {
        return priority.defaults();
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/priority-defaults/channels/{channel}")
    public PriorityDefaults.Channel setChannelDefault(@PathVariable String channel, @RequestBody(required = false) PriorityDefaultRequest request) {
        return priority.setChannelDefault(channel, request);
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/priority-defaults/services/{serviceId}")
    public PriorityDefaults.Service setServiceDefault(@PathVariable UUID serviceId, @RequestBody(required = false) PriorityDefaultRequest request) {
        return priority.setServiceDefault(serviceId, request);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/service-groups/{id}/routing-strategy")
    public RoutingStrategyView strategy(@PathVariable UUID id) {
        return priority.strategy(id);
    }

    @PreAuthorize(PERMISSION)
    @PutMapping("/service-groups/{id}/routing-strategy")
    public RoutingStrategyView setStrategy(@PathVariable UUID id, @RequestBody(required = false) RoutingStrategyRequest request) {
        return priority.setStrategy(id, request);
    }
}
