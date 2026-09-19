package com.qms.configuration.breaks;

import com.qms.configuration.site.Items;
import com.qms.platform.Profiles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

/** Break types (FR-AGT-020). Permissions are also enforced in {@link BreakTypeService} (API-016). */
@RestController
@Profile(Profiles.SERVING)
public class BreakTypeController {

    /** The reason a break type is switched off, kept in the audit log. */
    public record DeactivateRequest(@Size(max = 500) String reason) {}

    private final BreakTypeService breakTypes;

    BreakTypeController(BreakTypeService breakTypes) {
        this.breakTypes = breakTypes;
    }

    @PreAuthorize(BreakTypeService.READ)
    @GetMapping("/break-types")
    public Items<BreakType> types() {
        return new Items<>(breakTypes.types());
    }

    @PreAuthorize(BreakTypeService.MANAGE)
    @PostMapping("/break-types")
    @ResponseStatus(HttpStatus.CREATED)
    public BreakType create(@RequestBody BreakTypeRequest request) {
        return breakTypes.create(request);
    }

    @PreAuthorize(BreakTypeService.MANAGE)
    @PutMapping("/break-types/{id}")
    public BreakType replace(@PathVariable UUID id, @RequestBody BreakTypeRequest request) {
        return breakTypes.replace(id, request);
    }

    @PreAuthorize(BreakTypeService.MANAGE)
    @PostMapping("/break-types/{id}/deactivate")
    public BreakType deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return breakTypes.deactivate(id, request == null ? null : request.reason());
    }

    @PreAuthorize(BreakTypeService.MANAGE)
    @PostMapping("/break-types/{id}/activate")
    public BreakType activate(@PathVariable UUID id) {
        return breakTypes.activate(id);
    }
}
