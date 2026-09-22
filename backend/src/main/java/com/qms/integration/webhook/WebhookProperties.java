package com.qms.integration.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxAttempts how many times a delivery retries, with exponential backoff, before it is terminally
 *     {@code failed} (FR-INT-021, default 6); a manual replay from the delivery log tries again regardless
 * @param backoffBaseSeconds the backoff before a retry's Nth attempt: {@code backoffBaseSeconds * 2^(attempt - 1)}
 *     (FR-INT-021, default 30 seconds, the same base {@code NotificationProperties} uses)
 * @param sendPollCron when {@link WebhookDeliveryWorker} sweeps due deliveries; {@code -} disables it so a test can
 *     drive the sweep itself, the same convention {@code qms.notification.send-poll-cron} uses
 * @param requestTimeoutSeconds how long a single delivery attempt may take before it counts as failed (default 5
 *     seconds); a slow endpoint must never hold the worker, let alone the queue (FR-INT-022)
 * @param allowInsecureEndpointsForTests bypasses {@link WebhookEndpointSecurity}'s HTTPS-and-not-private-address
 *     check for a test's own local fake endpoint; no environment variable exposes this in application.yml
 */
@ConfigurationProperties("qms.webhook")
public record WebhookProperties(
        @DefaultValue("6") int maxAttempts,
        @DefaultValue("30") int backoffBaseSeconds,
        @DefaultValue("*/5 * * * * *") String sendPollCron,
        @DefaultValue("5") int requestTimeoutSeconds,
        @DefaultValue("false") boolean allowInsecureEndpointsForTests) {}
