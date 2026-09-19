package com.qms.identity;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.Profiles;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The Phase 1 identity provider: username and password against the local user table, with bcrypt, lockout and audit.
 * Unknown users, disabled users and wrong passwords all answer {@code invalid_credentials} after the same amount of
 * work, so none can be told apart from outside.
 */
@Component
@Profile(Profiles.SERVING)
class LocalPasswordProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalPasswordProvider.class);
    private static final int MAX_USERNAME_AUDIT_LENGTH = 100;

    private final UserRepository users;
    private final PasswordService passwords;
    private final PasswordPolicy policy;
    private final LoginLockout lockout;
    private final AuditWriter audit;
    private final Clock clock;

    LocalPasswordProvider(
            UserRepository users, PasswordService passwords, PasswordPolicy policy, LoginLockout lockout, AuditWriter audit, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.policy = policy;
        this.lockout = lockout;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public Authenticated authenticate(String username, String password) {
        Instant now = clock.instant();
        String name = username.trim();
        UserAccount user = users.findByUsername(name).orElse(null);

        if (user == null) {
            passwords.burn(password);
            audit.record(AuditEvent.of("auth.login.failed", "user", null)
                    .withAfter(Map.of("reason", "unknown_user", "username", truncate(name))));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (user.isLockedAt(now)) {
            log.warn("Sign-in refused, account is locked userId={}", user.id());
            throw LoginLockout.locked(user.lockedUntil(), now);
        }
        if (!user.active()) {
            passwords.burn(password);
            audit.record(AuditEvent.of("auth.login.failed", "user", user.id()).withAfter(Map.of("reason", "disabled")));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwords.matches(password, user.passwordHash())) {
            audit.record(AuditEvent.of("auth.login.failed", "user", user.id()).withAfter(Map.of("reason", "bad_password")));
            lockout.countFailure(user, now);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        users.resetFailures(user.id(), now);
        return new Authenticated(user.id(), policy.isExpired(user.passwordChangedAt(), now));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_USERNAME_AUDIT_LENGTH ? value : value.substring(0, MAX_USERNAME_AUDIT_LENGTH);
    }
}
