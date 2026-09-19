package com.qms.identity;

import com.qms.platform.security.Role;
import java.util.Set;
import java.util.UUID;

/**
 * Hook between a correct password and token issue, so multi-factor authentication for Org Admin and System
 * Administrator can be added in Phase 2 without changing any client flow (NFR-SEC-003). Phase 1 registers none.
 * A hook that is not satisfied makes login fail with {@code unauthenticated} and {@code details.step_up} naming the
 * factor required.
 */
public interface LoginStepUp {

    Result check(UUID userId, Set<Role> roles);

    record Result(boolean satisfied, String type) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result required(String type) {
            return new Result(false, type);
        }
    }
}
