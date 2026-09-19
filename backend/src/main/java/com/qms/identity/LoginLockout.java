package com.qms.identity;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Failed-login lockout (NFR-SEC-002): default 5 attempts then a 15-minute lock, logged and audited. */
@Component
class LoginLockout {

    private static final Logger log = LoggerFactory.getLogger(LoginLockout.class);

    private final UserRepository users;
    private final SecurityProperties properties;
    private final AuditWriter audit;

    LoginLockout(UserRepository users, SecurityProperties properties, AuditWriter audit) {
        this.users = users;
        this.properties = properties;
        this.audit = audit;
    }

    /** Counts one more failure. Throws {@code account_locked} if this failure reaches the threshold. */
    void countFailure(UserAccount user, Instant now) {
        var lockout = properties.lockout();
        UserRepository.FailureOutcome outcome = users.recordFailure(user.id(), lockout.maxAttempts(), lockout.duration(), now);
        if (outcome.locked()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("locked_until", outcome.lockedUntil().toString());
            details.put("max_attempts", lockout.maxAttempts());
            audit.record(AuditEvent.of("auth.lockout", "user", user.id()).withAfter(details));
            log.warn("Account locked after {} failed sign-in attempts userId={}", lockout.maxAttempts(), user.id());
            throw locked(outcome.lockedUntil(), now);
        }
    }

    static ApiException locked(Instant lockedUntil, Instant now) {
        long seconds = Math.max(1, (long) Math.ceil(Duration.between(now, lockedUntil).toMillis() / 1000.0));
        return new ApiException(ErrorCode.ACCOUNT_LOCKED, Map.of("retry_after_seconds", seconds));
    }
}
