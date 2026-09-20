package com.qms.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The email channel's SMTP settings (ticket 40, FR-INT-040, §14.1): "clients supply an SMTP relay for email"
 * (§27.2), so every value here is per-installation configuration, never a code change. A blank {@code host} means no
 * relay has been configured yet; {@link EmailChannel} fails cleanly with {@code smtp_not_configured} rather than
 * attempting a connection that could only ever fail (FR-NTF-033 then falls it back to the trigger's next channel).
 *
 * @param host the SMTP relay's hostname; blank (the default) leaves the channel unconfigured
 * @param port the SMTP relay's port (default 587, the common STARTTLS submission port)
 * @param username optional SMTP AUTH username; blank sends unauthenticated, for a relay that trusts this
 *     installation's own network instead (a common internal-relay setup)
 * @param password optional SMTP AUTH password, used only when {@code username} is set
 * @param starttls whether to negotiate STARTTLS before sending (default true)
 * @param from the {@code From} address every notification email is sent as
 * @param connectionTimeoutMillis how long a connection or a send may take before it counts as a failed attempt and
 *     falls to a retry or the trigger's next channel (FR-NTF-033), default 5 seconds
 */
@ConfigurationProperties("qms.notification.email")
public record NotificationEmailProperties(
        @DefaultValue("") String host,
        @DefaultValue("587") int port,
        String username,
        String password,
        @DefaultValue("true") boolean starttls,
        @DefaultValue("noreply@qms.local") String from,
        @DefaultValue("5000") int connectionTimeoutMillis) {}
