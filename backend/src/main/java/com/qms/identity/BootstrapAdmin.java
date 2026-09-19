package com.qms.identity;

import com.qms.audit.AuditEvent;
import com.qms.audit.AuditWriter;
import com.qms.platform.Profiles;
import com.qms.platform.security.Role;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-run System Administrator. Without one nobody could sign in. It is created only while there are no users at
 * all, from {@code QMS_BOOTSTRAP_ADMIN_USERNAME} and {@code QMS_BOOTSTRAP_ADMIN_PASSWORD} (NFR-SEC-013: secrets come
 * from the environment), and the password must pass the same policy as any other.
 */
@Component
@Profile(Profiles.SERVING)
class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final SecurityProperties properties;
    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final PasswordService passwords;
    private final PasswordPolicy policy;
    private final AuditWriter audit;
    private final Clock clock;

    BootstrapAdmin(
            SecurityProperties properties,
            UserRepository users,
            RoleAssignmentRepository assignments,
            PasswordService passwords,
            PasswordPolicy policy,
            AuditWriter audit,
            Clock clock) {
        this.properties = properties;
        this.users = users;
        this.assignments = assignments;
        this.passwords = passwords;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var bootstrap = properties.bootstrap();
        if (bootstrap.username() == null || bootstrap.username().isBlank() || bootstrap.password() == null || bootstrap.password().isBlank()) {
            return;
        }
        if (users.count() > 0) {
            return;
        }
        List<String> violations = policy.violations(bootstrap.password(), List.of(), passwords.encoder());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("QMS_BOOTSTRAP_ADMIN_PASSWORD does not satisfy the password policy: " + violations);
        }
        UUID id = users.insert(bootstrap.username().trim(), passwords.hash(bootstrap.password()), null, null, clock.instant());
        assignments.replaceAll(id, List.of(new RoleAssignment(Role.SYSTEM_ADMIN, Set.of(), Set.of())));
        audit.record(AuditEvent.of("user.bootstrap_created", "user", id).withActor(id, Role.SYSTEM_ADMIN.wire()));
        log.info("Created the first System Administrator username={}", bootstrap.username().trim());
    }
}
