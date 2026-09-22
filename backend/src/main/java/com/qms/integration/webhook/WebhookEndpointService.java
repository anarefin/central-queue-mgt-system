package com.qms.integration.webhook;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.integration.webhook.WebhookEndpointRepository.Row;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD of webhook endpoints (FR-INT-020): organisation-wide configuration, the same
 * {@code config:org_sites_zones} permission {@code configuration.breaks.BreakTypeService} already uses for a
 * comparably small, org-wide admin surface, checked here at the service layer (API-016). Every change writes an
 * audit entry with before/after values (FR-SEC-040); the secret is never written to the audit log in the clear
 * ({@code audit.AuditRedaction} masks any {@code secret} key regardless, but this service also never passes it in
 * the first place).
 */
@Service
public class WebhookEndpointService {

    static final String MANAGE = "hasAuthority(T(com.qms.platform.security.Authorities).CONFIG_ORG_SITES_ZONES)";
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository repository;
    private final AuditWriter audit;
    private final WebhookProperties properties;
    private final Clock clock;

    WebhookEndpointService(WebhookEndpointRepository repository, AuditWriter audit, WebhookProperties properties, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public List<WebhookEndpointView> list() {
        return repository.all().stream().map(this::view).toList();
    }

    @PreAuthorize(MANAGE)
    @Transactional(readOnly = true)
    public WebhookEndpointView get(UUID id) {
        return view(require(id));
    }

    /** The secret is the request's own, trimmed, or a fresh random one when left blank; either way it is shown back
     * exactly once, in this response (FR-INT-020). */
    @PreAuthorize(MANAGE)
    @Transactional
    public WebhookEndpointView create(WebhookEndpointRequest request, UUID actorId) {
        String description = WebhookEndpointRules.description(request.description());
        String url = WebhookEndpointRules.url(request.url(), properties.allowInsecureEndpointsForTests());
        List<String> eventTypes = WebhookEndpointRules.eventTypes(request.eventTypes());
        String secret = request.secret() == null || request.secret().isBlank() ? generateSecret() : request.secret().trim();

        UUID id = repository.insert(description, url, eventTypes, secret, actorId, clock.instant());
        audit.record(AuditEvent.of("webhook_endpoint.created", "webhook_endpoint", id).withAfter(snapshot(description, url, eventTypes, true)));
        return view(require(id)).withSecret(secret);
    }

    /** Replaces the description, URL and event types as a whole; the secret is untouched ({@link #rotateSecret} alone changes it). */
    @PreAuthorize(MANAGE)
    @Transactional
    public WebhookEndpointView update(UUID id, WebhookEndpointRequest request) {
        Row before = require(id);
        String description = WebhookEndpointRules.description(request.description());
        String url = WebhookEndpointRules.url(request.url(), properties.allowInsecureEndpointsForTests());
        List<String> eventTypes = WebhookEndpointRules.eventTypes(request.eventTypes());
        if (description.equals(before.description()) && url.equals(before.url()) && eventTypes.equals(before.eventTypes())) return view(before);

        repository.update(id, description, url, eventTypes, clock.instant());
        audit.record(
                AuditEvent.of("webhook_endpoint.updated", "webhook_endpoint", id)
                        .withBefore(snapshot(before.description(), before.url(), before.eventTypes(), before.active()))
                        .withAfter(snapshot(description, url, eventTypes, before.active())));
        return view(require(id));
    }

    /** A fresh secret, shown back exactly once, the same as {@link #create}; the old one stops verifying at once. */
    @PreAuthorize(MANAGE)
    @Transactional
    public WebhookEndpointView rotateSecret(UUID id) {
        require(id);
        String secret = generateSecret();
        repository.rotateSecret(id, secret, clock.instant());
        audit.record(AuditEvent.of("webhook_endpoint.secret_rotated", "webhook_endpoint", id));
        return view(require(id)).withSecret(secret);
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public WebhookEndpointView deactivate(UUID id) {
        Row current = require(id);
        if (!current.active()) return view(current);
        repository.setActive(id, false, clock.instant());
        audit.record(activeFlag("webhook_endpoint.deactivated", id, false));
        return view(require(id));
    }

    @PreAuthorize(MANAGE)
    @Transactional
    public WebhookEndpointView activate(UUID id) {
        Row current = require(id);
        if (current.active()) return view(current);
        repository.setActive(id, true, clock.instant());
        audit.record(activeFlag("webhook_endpoint.activated", id, true));
        return view(require(id));
    }

    private Row require(UUID id) {
        return repository.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static AuditEvent activeFlag(String action, UUID id, boolean active) {
        return AuditEvent.of(action, "webhook_endpoint", id).withBefore(Map.of("active", !active)).withAfter(Map.of("active", active));
    }

    private static Map<String, Object> snapshot(String description, String url, List<String> eventTypes, boolean active) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("description", description);
        values.put("url", url);
        values.put("event_types", eventTypes);
        values.put("active", active);
        return values;
    }

    private WebhookEndpointView view(Row row) {
        return new WebhookEndpointView(row.id(), row.description(), row.url(), row.eventTypes(), row.active(), row.createdAt(), row.updatedAt(), null);
    }
}
