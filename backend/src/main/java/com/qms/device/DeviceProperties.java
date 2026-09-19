package com.qms.device;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Device pairing and credential settings (ticket 24). The SRS requires only that a pairing code be "short-lived"
 * (FR-OPS-011) and a device credential be "rotatable" (NFR-SEC-005); it gives no numeric defaults, so these are this
 * build's choice, configurable per installation like {@code qms.security}'s own defaults.
 *
 * @param pairingCodeTtl   how long a pairing code may be redeemed before it expires
 * @param refreshTokenTtl  how long a device's refresh credential is valid from issuance; devices have no human present
 *                         to keep an idle window sliding, so this is a fixed lifetime, not an idle timeout
 */
@ConfigurationProperties("qms.device")
public record DeviceProperties(
        @DefaultValue("PT10M") Duration pairingCodeTtl, @DefaultValue("P90D") Duration refreshTokenTtl) {}
