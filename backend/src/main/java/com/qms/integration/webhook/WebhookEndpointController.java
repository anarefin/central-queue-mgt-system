package com.qms.integration.webhook;

import com.qms.platform.Profiles;
import com.qms.platform.security.CurrentUser;
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

/** Admin surface for webhook endpoints (ticket 57, FR-INT-020). Permission is also enforced in
 * {@link WebhookEndpointService} (API-016). */
@RestController
@Profile(Profiles.SERVING)
public class WebhookEndpointController {

    private final WebhookEndpointService service;
    private final CurrentUser currentUser;

    WebhookEndpointController(WebhookEndpointService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @GetMapping("/webhook-endpoints")
    public Items<WebhookEndpointView> list() {
        return new Items<>(service.list());
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @GetMapping("/webhook-endpoints/{id}")
    public WebhookEndpointView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @PostMapping("/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointView create(@RequestBody WebhookEndpointRequest request) {
        return service.create(request, currentUser.require().userId());
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @PutMapping("/webhook-endpoints/{id}")
    public WebhookEndpointView update(@PathVariable UUID id, @RequestBody WebhookEndpointRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @PostMapping("/webhook-endpoints/{id}/rotate-secret")
    public WebhookEndpointView rotateSecret(@PathVariable UUID id) {
        return service.rotateSecret(id);
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @PostMapping("/webhook-endpoints/{id}/deactivate")
    public WebhookEndpointView deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PreAuthorize(WebhookEndpointService.MANAGE)
    @PostMapping("/webhook-endpoints/{id}/activate")
    public WebhookEndpointView activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}
