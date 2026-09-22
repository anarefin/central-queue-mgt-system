package com.qms.integration.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** The editable content of a webhook endpoint (FR-INT-020): a description, the URL to POST to, and the §21.4 event
 * types it subscribes to. {@code secret} is accepted only on create, where a blank or absent one is generated
 * server-side; update never touches the secret ({@link WebhookEndpointController#rotateSecret} does that alone). */
public record WebhookEndpointRequest(
        String description, String url, @JsonProperty("event_types") List<String> eventTypes, String secret) {}
