package com.qms.issuance.bundle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The shared secret a Priority/routing/numbering/business-hours bundle is HMAC-signed with (CFG-004). It must be set
 * the same on the source installation that exports and the target installation that imports (a staging or training
 * environment "of the same version", SRS §3.7), so it is never generated per-instance the way the JWT signing key is:
 * a value neither side already shares cannot verify the other's signature. A blank secret (the default) leaves the
 * bundle feature unconfigured; export and import both fail clean with {@code bundle_secret_not_configured}.
 *
 * @param secret the shared HMAC-SHA256 key, as any non-blank string
 */
@ConfigurationProperties("qms.config.bundle")
public record ConfigBundleProperties(@DefaultValue("") String secret) {}
