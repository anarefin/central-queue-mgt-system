package com.qms.identity;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings. Everything here is configurable per installation, but the two hard limits from the SRS are
 * enforced at startup rather than trusted: access tokens live at most 15 minutes (API-013) and bcrypt cost is at
 * least 12 (NFR-SEC-001).
 *
 * @param issuer             {@code iss} claim; set it per installation
 * @param keyDir             where the per-installation signing keys live; outside source control (API-015, NFR-SEC-013)
 * @param keyRotationOverlap how long a retired key's public half keeps validating tokens after a rotation
 * @param keyRefreshInterval how often a running node re-reads {@code keyDir}, so it picks up a rotation done elsewhere
 */
@ConfigurationProperties("qms.security")
public record SecurityProperties(
        @DefaultValue("https://qms.local") String issuer,
        @DefaultValue("qms-api") String audience,
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("./keys") Path keyDir,
        @DefaultValue("P1D") Duration keyRotationOverlap,
        @DefaultValue("PT30S") Duration keyRefreshInterval,
        @DefaultValue Lockout lockout,
        @DefaultValue Idle idle,
        @DefaultValue PasswordRules password,
        @DefaultValue Cookie refreshCookie,
        @DefaultValue Bootstrap bootstrap) {

    public static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    public SecurityProperties {
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero() || accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException(
                    "qms.security.access-token-ttl must be positive and at most 15 minutes (API-013), was " + accessTokenTtl);
        }
        if (keyRotationOverlap.compareTo(accessTokenTtl) < 0) {
            throw new IllegalArgumentException(
                    "qms.security.key-rotation-overlap must be at least the access token lifetime, or a rotation would cut off live tokens");
        }
        if (password.bcryptCost() < 12) {
            throw new IllegalArgumentException("qms.security.password.bcrypt-cost must be at least 12 (NFR-SEC-001)");
        }
    }

    /** Defaults with an explicit key directory; used by tests and tools. */
    public static SecurityProperties forKeyDir(Path keyDir) {
        return new SecurityProperties(
                "https://qms.local",
                "qms-api",
                MAX_ACCESS_TOKEN_TTL,
                keyDir,
                Duration.ofDays(1),
                Duration.ofSeconds(30),
                new Lockout(5, Duration.ofMinutes(15)),
                new Idle(Duration.ofMinutes(30), Duration.ofHours(12)),
                new PasswordRules(12, 3, 5, 0, 12),
                new Cookie("qms_refresh", true),
                new Bootstrap(null, null));
    }

    public SecurityProperties withAccessTokenTtl(Duration ttl) {
        return new SecurityProperties(issuer, audience, ttl, keyDir, keyRotationOverlap, keyRefreshInterval, lockout, idle, password, refreshCookie, bootstrap);
    }

    public SecurityProperties withLockout(Lockout lockout) {
        return new SecurityProperties(issuer, audience, accessTokenTtl, keyDir, keyRotationOverlap, keyRefreshInterval, lockout, idle, password, refreshCookie, bootstrap);
    }

    public SecurityProperties withPassword(PasswordRules password) {
        return new SecurityProperties(issuer, audience, accessTokenTtl, keyDir, keyRotationOverlap, keyRefreshInterval, lockout, idle, password, refreshCookie, bootstrap);
    }

    /** NFR-SEC-002: failed-login lockout, default 5 attempts then a 15-minute lock. */
    public record Lockout(@DefaultValue("5") int maxAttempts, @DefaultValue("PT15M") Duration duration) {}

    /** NFR-SEC-004: refresh-token inactivity limits. Admin roles are shorter than agent consoles. */
    public record Idle(@DefaultValue("PT30M") Duration admin, @DefaultValue("PT12H") Duration agent) {}

    /**
     * NFR-SEC-001: configurable password policy. {@code maxAgeDays} of 0 means passwords do not expire.
     * The SRS gives no defaults for these; the values below are this build's choice.
     */
    public record PasswordRules(
            @DefaultValue("12") int minLength,
            @DefaultValue("3") int minCharacterClasses,
            @DefaultValue("5") int historyCount,
            @DefaultValue("0") int maxAgeDays,
            @DefaultValue("12") int bcryptCost) {}

    /** API-017: the refresh token lives in an HttpOnly, Secure, SameSite=Strict cookie. */
    public record Cookie(@DefaultValue("qms_refresh") String name, @DefaultValue("true") boolean secure) {}

    /** First-run System Administrator, created only while there are no users (NFR-SEC-013: from the environment). */
    public record Bootstrap(String username, String password) {}
}
