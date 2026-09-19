package com.qms.identity;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff sessions: sign-in through the {@link IdentityProvider}, refresh-token rotation with reuse detection, sign-out
 * and password change. Methods keep their writes when they end by throwing an {@link ApiException}, because the failure
 * itself (a counted bad password, a revoked token family) is the state that must persist.
 */
@Service
@Profile(Profiles.SERVING)
class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityProvider identityProvider;
    private final LoginLockout lockout;
    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final PasswordService passwords;
    private final PasswordPolicy policy;
    private final IdlePolicy idlePolicy;
    private final SecurityProperties properties;
    private final AuditWriter audit;
    private final Clock clock;
    private final List<LoginStepUp> stepUps;

    AuthService(
            IdentityProvider identityProvider,
            LoginLockout lockout,
            UserRepository users,
            RoleAssignmentRepository assignments,
            RefreshTokenRepository refreshTokens,
            AccessTokenService accessTokens,
            PasswordService passwords,
            PasswordPolicy policy,
            IdlePolicy idlePolicy,
            SecurityProperties properties,
            AuditWriter audit,
            Clock clock,
            List<LoginStepUp> stepUps) {
        this.identityProvider = identityProvider;
        this.lockout = lockout;
        this.users = users;
        this.assignments = assignments;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwords = passwords;
        this.policy = policy;
        this.idlePolicy = idlePolicy;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        this.stepUps = stepUps;
    }

    // ---- sign in -----------------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    Session login(String username, String password) {
        Instant now = clock.instant();
        IdentityProvider.Authenticated identity = identityProvider.authenticate(username, password);

        EffectiveAccess access = EffectiveAccess.of(assignments.findByUser(identity.userId()));
        for (LoginStepUp hook : stepUps) {
            LoginStepUp.Result result = hook.check(identity.userId(), access.roles());
            if (!result.satisfied()) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, Map.of("step_up", result.type()));
            }
        }

        Session session = issueSession(identity.userId(), access, UUID.randomUUID(), now, identity.passwordExpired());
        audit.record(AuditEvent.of("auth.login.succeeded", "user", identity.userId()).withActor(identity.userId(), rolesCsv(access)));
        return session;
    }

    // ---- silent refresh ----------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        Instant now = clock.instant();
        RefreshTokenRepository.Row row = refreshTokens.lockByHash(hash(rawToken)).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));

        if (row.usedAt() != null) {
            // Single use: a token that was already exchanged is being replayed, so the whole family is untrustworthy.
            refreshTokens.revokeFamily(row.familyId(), now);
            audit.record(AuditEvent.of("auth.refresh.reuse_detected", "user", row.userId())
                    .withAfter(Map.of("family_id", row.familyId().toString())));
            log.warn("Refresh token reuse detected, family revoked userId={} familyId={}", row.userId(), row.familyId());
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        if (row.revokedAt() != null || !row.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.TOKEN_INVALID); // signed out, or idle for longer than the allowed period
        }
        UserAccount user = users.findById(row.userId()).orElse(null);
        if (user == null || !user.active()) {
            refreshTokens.revokeFamily(row.familyId(), now);
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }

        refreshTokens.markUsed(row.id(), now);
        EffectiveAccess access = EffectiveAccess.of(assignments.findByUser(user.id())); // role changes apply here
        return issueSession(user.id(), access, row.familyId(), now, policy.isExpired(user.passwordChangedAt(), now));
    }

    // ---- sign out ----------------------------------------------------------------------------------------------

    @Transactional
    void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        Instant now = clock.instant();
        refreshTokens.lockByHash(hash(rawToken)).ifPresent(row -> {
            refreshTokens.revokeFamily(row.familyId(), now);
            audit.record(AuditEvent.of("auth.logout", "user", row.userId()).withActor(row.userId(), null));
        });
    }

    // ---- change password ---------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    void changePassword(UUID userId, String currentPassword, String newPassword) {
        Instant now = clock.instant();
        UserAccount user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (user.isLockedAt(now)) {
            throw LoginLockout.locked(user.lockedUntil(), now);
        }
        if (!passwords.matches(currentPassword, user.passwordHash())) {
            audit.record(AuditEvent.of("auth.password.change_failed", "user", user.id()).withAfter(Map.of("reason", "bad_current_password")));
            lockout.countFailure(user, now); // a stolen access token must not become a way to guess the password
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        List<String> previous = new ArrayList<>(users.recentPasswordHashes(userId, Math.max(properties.password().historyCount(), 1)));
        if (!previous.contains(user.passwordHash())) previous.add(0, user.passwordHash());
        List<String> violations = policy.violations(newPassword, previous, passwords.encoder());
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "new_password", "rules", violations));
        }

        users.changePassword(userId, passwords.hash(newPassword), now);
        refreshTokens.revokeAllForUser(userId, now); // every existing session ends; the user signs in again
        audit.record(AuditEvent.of("auth.password.changed", "user", userId));
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private Session issueSession(UUID userId, EffectiveAccess access, UUID familyId, Instant now, boolean passwordExpired) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration idle = idlePolicy.idleFor(access.roles());
        refreshTokens.insert(UUID.randomUUID(), familyId, userId, hash(raw), now, now.plus(idle));
        AccessToken accessToken = accessTokens.issue(userId, access.roles(), access.siteIds(), access.groupIds());
        return new Session(accessToken, raw, idle, passwordExpired);
    }

    static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rolesCsv(EffectiveAccess access) {
        return access.roles().stream().map(r -> r.wire()).sorted().reduce((a, b) -> a + "," + b).orElse(null);
    }
}
