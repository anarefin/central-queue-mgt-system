package com.qms.notification;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web Push channel settings (ticket 39, FR-INT-040, NFR-SEC-013).
 *
 * @param keyDir where the per-installation VAPID key pair lives; outside source control, the same convention
 *     {@code qms.security.key-dir} uses for the staff-token signing keys
 * @param subject the VAPID JWT's {@code sub} claim (RFC 8292): a contact URI a push service may reach this
 *     installation's operator through, conventionally {@code mailto:...} or an {@code https://} URL
 * @param jwtTtl how long a VAPID JWT is valid for; a fresh one is signed per send, so this only bounds how long a
 *     captured header could be replayed
 * @param ttlSeconds the {@code TTL} header on a push request: how long the push service may hold the message for a
 *     device that is offline, in seconds
 * @param allowInsecureEndpointsForTests skips {@link PushEndpointSecurity}'s HTTPS-and-not-private-address check
 *     (SSRF hardening); exists only so a test's own local fake push service can be reached, defaults to {@code
 *     false}, and no environment variable in {@code application.yml} wires it — an operator cannot enable it by
 *     accident
 */
@ConfigurationProperties("qms.notification.web-push")
public record WebPushProperties(
        @DefaultValue("./keys/vapid") Path keyDir,
        @DefaultValue("mailto:admin@qms.local") String subject,
        @DefaultValue("PT12H") Duration jwtTtl,
        @DefaultValue("2419200") int ttlSeconds,
        @DefaultValue("false") boolean allowInsecureEndpointsForTests) {}
