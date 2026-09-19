package com.qms.identity;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.Role;
import com.qms.platform.security.Scope;
import java.util.List;
import java.util.Set;

/**
 * Nobody may grant more than they hold. The SRS §5.2 matrix says who may assign roles but not how far, so the build
 * applies two rules: only a System Administrator can create or manage a System Administrator, and a scoped caller may
 * only hand out assignments that are themselves scoped inside their own sites and groups. Both close an escalation
 * path (an empty scope means "everything").
 */
final class AssignmentGuard {

    private AssignmentGuard() {}

    static void checkAssignable(AuthenticatedUser caller, List<RoleAssignment> requested) {
        for (RoleAssignment assignment : requested) {
            if (assignment.role() == Role.SYSTEM_ADMIN && !caller.roles().contains(Role.SYSTEM_ADMIN)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            checkDimension(caller.siteIds(), assignment.siteIds());
            checkDimension(caller.groupIds(), assignment.groupIds());
            boolean callerScoped = !caller.siteIds().isEmpty() || !caller.groupIds().isEmpty();
            if (callerScoped && assignment.isOrganisationWide()) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (!caller.siteIds().isEmpty() && assignment.siteIds().isEmpty()) {
                throw new ApiException(ErrorCode.FORBIDDEN); // would not be limited to the caller's sites
            }
            if (!caller.groupIds().isEmpty() && assignment.groupIds().isEmpty()) {
                throw new ApiException(ErrorCode.FORBIDDEN); // would not be limited to the caller's groups
            }
        }
    }

    /** Editing, disabling or re-enabling a System Administrator is reserved to System Administrators. */
    static void checkTargetManageable(AuthenticatedUser caller, Set<Role> targetRoles) {
        if (targetRoles.contains(Role.SYSTEM_ADMIN) && !caller.roles().contains(Role.SYSTEM_ADMIN)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private static void checkDimension(Set<java.util.UUID> callerClaim, Set<java.util.UUID> requested) {
        for (java.util.UUID id : requested) {
            Scope.require(callerClaim, id);
        }
    }
}
