package com.qms.mobile;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.identity.AccessToken;
import com.qms.identity.AccessTokenService;
import com.qms.mobile.VisitorAuthRepository.OtpRow;
import com.qms.mobile.VisitorAuthRepository.RefreshRow;
import com.qms.mobile.VisitorAuthRepository.VisitorRow;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import com.qms.platform.security.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email + OTP sign-in for a registered visitor (ticket 41, FR-MOB-001, §20.2): a visitor unknown by email is given a
 * fresh visitor record on their first successful code, exactly as a walk-in gets one at reception (ticket 22) — so
 * "registering" is simply signing in for the first time. OTPs are single-use, short-lived, attempt-limited and
 * request-rate-limited (API-090's own shape, §20.6), never logged and never returned in any response (API-018); only
 * a SHA-256 hash of the code is ever persisted, the same as every other secret this codebase stores (staff refresh
 * tokens, device refresh tokens, ticket secrets).
 */
@Service
@Profile(Profiles.SERVING)
class VisitorAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VisitorAuthRepository repository;
    private final VisitorOtpMailer mailer;
    private final AccessTokenService accessTokens;
    private final VisitorAuthProperties properties;
    private final AuditWriter audit;
    private final Clock clock;

    VisitorAuthService(
            VisitorAuthRepository repository,
            VisitorOtpMailer mailer,
            AccessTokenService accessTokens,
            VisitorAuthProperties properties,
            AuditWriter audit,
            Clock clock) {
        this.repository = repository;
        this.mailer = mailer;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- request an OTP ------------------------------------------------------------------------------------------

    @Transactional
    void requestOtp(String rawEmail) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();

        // Serialises the rate check with the insert it guards, the same pattern IssuanceGate uses for API-090's
        // other half (per-device, per-visitor issuance limits).
        repository.lock("visitor_otp:" + email);
        Instant windowStart = now.minus(Duration.ofHours(1));
        int recent = repository.countRequestsSince(email, windowStart);
        if (recent >= properties.requestLimitPerHour()) {
            long seconds = Duration.ofHours(1).toSeconds();
            throw new ApiException(
                    ErrorCode.RATE_LIMITED,
                    "auth.refused.otp_rate_limited",
                    new Object[0],
                    Map.of("reason", "otp_rate_limited", "retry_after_seconds", seconds, "limit", properties.requestLimitPerHour()));
        }

        String code = newCode();
        repository.insertOtp(email, hash(code), now, now.plus(properties.codeTtl()));
        mailer.sendCode(email, code, properties.codeTtl());
        audit.record(AuditEvent.of("auth.visitor.otp_requested", "visitor_otp", null).withAfter(Map.of("email", masked(email))));
    }

    // ---- verify an OTP, signing in ---------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    VisitorSession verifyOtp(String rawEmail, String rawCode) {
        String email = normalize(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        Instant now = clock.instant();

        OtpRow otp = repository.lockLatestOpen(email).orElse(null);
        if (otp == null || !otp.expiresAt().isAfter(now)) {
            throw invalidCode();
        }
        if (otp.attempts() >= properties.maxVerifyAttempts() || !MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.UTF_8), otp.codeHash().getBytes(StandardCharsets.UTF_8))) {
            repository.incrementAttempts(otp.id());
            if (otp.attempts() + 1 >= properties.maxVerifyAttempts()) repository.markConsumed(otp.id(), now); // locked out; a fresh code is required
            audit.record(AuditEvent.of("auth.visitor.otp_failed", "visitor_otp", otp.id()).withAfter(Map.of("email", masked(email))));
            throw invalidCode();
        }
        repository.markConsumed(otp.id(), now);

        VisitorRow visitor = repository.findByEmail(email).orElse(null);
        UUID visitorId = visitor != null ? visitor.id() : repository.insertVisitor(email, now);
        repository.markEmailVerified(visitorId, now);

        VisitorSession session = issueSession(visitorId, UUID.randomUUID(), now);
        audit.record(AuditEvent.of("auth.visitor.login_succeeded", "visitor", visitorId).withActor(visitorId, Role.VISITOR.wire()));
        return session;
    }

    // ---- silent refresh ------------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    VisitorSession refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new ApiException(ErrorCode.TOKEN_INVALID);
        Instant now = clock.instant();
        RefreshRow row = repository.lockByHash(hash(rawToken)).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));

        if (row.usedAt() != null) {
            repository.revokeFamily(row.familyId(), now);
            audit.record(AuditEvent.of("auth.visitor.refresh.reuse_detected", "visitor", row.visitorId())
                    .withAfter(Map.of("family_id", row.familyId().toString())));
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        if (row.revokedAt() != null || !row.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        if (repository.findById(row.visitorId()).isEmpty()) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }

        repository.markRefreshUsed(row.id(), now);
        return issueSession(row.visitorId(), row.familyId(), now);
    }

    // ---- sign out ----------------------------------------------------------------------------------------------

    @Transactional
    void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        Instant now = clock.instant();
        repository.lockByHash(hash(rawToken)).ifPresent(row -> {
            repository.revokeFamily(row.familyId(), now);
            audit.record(AuditEvent.of("auth.visitor.logout", "visitor", row.visitorId()).withActor(row.visitorId(), Role.VISITOR.wire()));
        });
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private VisitorSession issueSession(UUID visitorId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl = properties.refreshTokenTtl();
        repository.insertRefreshToken(UUID.randomUUID(), familyId, visitorId, hash(raw), now, now.plus(ttl));
        AccessToken accessToken = accessTokens.issue(visitorId, Set.of(Role.VISITOR), Set.of(), Set.of());
        return new VisitorSession(accessToken, raw, ttl);
    }

    private String newCode() {
        int bound = (int) Math.pow(10, properties.codeLength());
        int value = RANDOM.nextInt(bound);
        return String.format("%0" + properties.codeLength() + "d", value);
    }

    private static ApiException invalidCode() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "auth.refused.otp_invalid", new Object[0], Map.of("reason", "otp_invalid"));
    }

    private static String normalize(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", java.util.List.of(Map.of("field", "email", "code", "invalid"))));
        }
        return email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** For the audit log only: never the OTP, and not the full address either (API-018's spirit extended to PII). */
    private static String masked(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }

    static String hash(String rawValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
