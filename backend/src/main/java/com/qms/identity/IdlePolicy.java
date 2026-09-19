package com.qms.identity;

import com.qms.platform.security.Role;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Idle timeout by refresh-token inactivity (NFR-SEC-004): admin roles are shorter than agent consoles. A user with
 * several roles gets the shortest applicable limit, and a user with none gets the admin limit (the safe side).
 */
@Component
class IdlePolicy {

    private final SecurityProperties.Idle idle;

    IdlePolicy(SecurityProperties properties) {
        this.idle = properties.idle();
    }

    Duration idleFor(Set<Role> roles) {
        if (roles.isEmpty()) return idle.admin();
        return roles.stream().map(role -> role.isAdmin() ? idle.admin() : idle.agent()).min(Duration::compareTo).orElseThrow();
    }
}
