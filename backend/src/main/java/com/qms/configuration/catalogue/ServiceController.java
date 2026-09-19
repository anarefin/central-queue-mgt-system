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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Services, the counters that serve them and their outcome codes. */
@RestController
@RequestMapping("/services")
@Profile(Profiles.SERVING)
public class ServiceController {

    private static final String PERMISSION = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_SERVICE_CATALOGUE)";

    private final CatalogueService catalogue;

    ServiceController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}")
    public ServiceEntry get(@PathVariable UUID id) {
        return catalogue.service(id);
    }

    @PreAuthorize(PERMISSION)
    @PatchMapping("/{id}")
    public ServiceEntry update(@PathVariable UUID id, @RequestBody UpdateServiceRequest request) {
        return catalogue.updateService(id, request);
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/deactivate")
    public ServiceEntry deactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) DeactivateRequest request) {
        return catalogue.deactivateService(id, request == null ? null : request.reason());
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/activate")
    public ServiceEntry activate(@PathVariable UUID id) {
        return catalogue.activateService(id);
    }

    /** Refused with {@code conflict} once tickets refer to the service; deactivate it instead (FR-CFG-015). */
    @PreAuthorize(PERMISSION)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        catalogue.deleteService(id);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}/counters")
    public Items<CounterLink> links(@PathVariable UUID id) {
        return new Items<>(catalogue.links(id));
    }

    /** Links the counter, or changes the weight of an existing link (FR-CFG-011). */
    @PreAuthorize(PERMISSION)
    @PutMapping("/{id}/counters/{counterId}")
    public CounterLink link(@PathVariable UUID id, @PathVariable UUID counterId, @RequestBody(required = false) LinkCounterRequest request) {
        return catalogue.linkCounter(id, counterId, request == null ? null : request.preferenceWeight());
    }

    @PreAuthorize(PERMISSION)
    @DeleteMapping("/{id}/counters/{counterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable UUID id, @PathVariable UUID counterId) {
        catalogue.unlinkCounter(id, counterId);
    }

    @PreAuthorize(PERMISSION)
    @GetMapping("/{id}/outcome-codes")
    public Items<OutcomeCode> outcomes(@PathVariable UUID id) {
        return new Items<>(catalogue.outcomes(id));
    }

    @PreAuthorize(PERMISSION)
    @PostMapping("/{id}/outcome-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public OutcomeCode createOutcome(@PathVariable UUID id, @RequestBody CreateOutcomeCodeRequest request) {
        return catalogue.createOutcome(id, request);
    }
}
