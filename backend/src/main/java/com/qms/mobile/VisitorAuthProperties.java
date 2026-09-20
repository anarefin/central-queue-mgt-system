package com.qms.mobile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Visitor email + OTP sign-in settings (ticket 41, FR-MOB-001). The SRS gives no numeric defaults for the OTP
 * itself, so these are this build's choice, configurable per installation like {@code qms.device}'s own defaults.
 * {@code requestLimitPerHour} is this feature's own instance of API-090's shape ("issuance endpoints... rate-limited
 * per visitor... 5 per hour"), applied to the OTP a visitor requests instead of a ticket.
 *
 * @param codeLength how many digits an OTP has
 * @param codeTtl how long an issued OTP may still be verified
 * @param maxVerifyAttempts wrong guesses allowed against one issued OTP before it is locked out; a further guess
 *     against it answers {@code invalid_credentials} the same as any other wrong code, never revealing that it is
 *     now locked, and the visitor must request a fresh one
 * @param requestLimitPerHour how many OTPs one email address may request per rolling hour
 * @param refreshTokenTtl how long a visitor's refresh credential is valid from issuance
 */
@ConfigurationProperties("qms.visitor.auth")
public record VisitorAuthProperties(
        @DefaultValue("6") int codeLength,
        @DefaultValue("PT10M") Duration codeTtl,
        @DefaultValue("5") int maxVerifyAttempts,
        @DefaultValue("5") int requestLimitPerHour,
        @DefaultValue("P30D") Duration refreshTokenTtl) {}
