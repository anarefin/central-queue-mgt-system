package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Nobody may grant more than they hold: role escalation and scope escalation are both refused (FR-CFG-106). */
class AssignmentGuardTest {

    private static final UUID SITE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SITE_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID GROUP_A = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID GROUP_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static AuthenticatedUser caller(Role role, Set<UUID> sites, Set<UUID> groups) {
        return new AuthenticatedUser(UUID.randomUUID(), Set.of(role), sites, groups);
    }

    private static RoleAssignment assignment(Role role, Set<UUID> sites, Set<UUID> groups) {
        return new RoleAssignment(role, sites, groups);
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void systemAdministratorMayAssignAnyRoleAtAnyScope() {
        var admin = caller(Role.SYSTEM_ADMIN, Set.of(), Set.of());
        assertThatCode(() -> AssignmentGuard.checkAssignable(admin, List.of(
                        assignment(Role.SYSTEM_ADMIN, Set.of(), Set.of()),
                        assignment(Role.AGENT, Set.of(SITE_A), Set.of(GROUP_B)))))
                .doesNotThrowAnyException();
    }

    @Test
    void anOrgAdminCannotCreateASystemAdministrator() {
        var orgAdmin = caller(Role.ORG_ADMIN, Set.of(), Set.of());
        assertForbidden(() -> AssignmentGuard.checkAssignable(orgAdmin, List.of(assignment(Role.SYSTEM_ADMIN, Set.of(), Set.of()))));
        assertThatCode(() -> AssignmentGuard.checkAssignable(orgAdmin, List.of(assignment(Role.ORG_ADMIN, Set.of(), Set.of()))))
                .doesNotThrowAnyException();
    }

    @Test
    void aSiteScopedOrgAdminStaysInsideItsSites() {
        var siteAdmin = caller(Role.ORG_ADMIN, Set.of(SITE_A), Set.of());

        assertThatCode(() -> AssignmentGuard.checkAssignable(siteAdmin, List.of(assignment(Role.AGENT, Set.of(SITE_A), Set.of()))))
                .doesNotThrowAnyException();
        assertForbidden(() -> AssignmentGuard.checkAssignable(siteAdmin, List.of(assignment(Role.AGENT, Set.of(SITE_B), Set.of()))));
        assertForbidden(() -> AssignmentGuard.checkAssignable(siteAdmin, List.of(assignment(Role.AGENT, Set.of(SITE_A, SITE_B), Set.of()))));
    }

    @Test
    void aScopedCallerCannotGrantAnOrganisationWideOrSiteLessAssignment() {
        var siteAdmin = caller(Role.ORG_ADMIN, Set.of(SITE_A), Set.of());

        assertForbidden(() -> AssignmentGuard.checkAssignable(siteAdmin, List.of(assignment(Role.AGENT, Set.of(), Set.of()))));
        assertForbidden(() -> AssignmentGuard.checkAssignable(siteAdmin, List.of(assignment(Role.AGENT, Set.of(), Set.of(GROUP_A)))));
    }

    @Test
    void aGroupScopedCallerStaysInsideItsGroups() {
        var groupAdmin = caller(Role.ORG_ADMIN, Set.of(), Set.of(GROUP_A));

        assertThatCode(() -> AssignmentGuard.checkAssignable(groupAdmin, List.of(assignment(Role.AGENT, Set.of(), Set.of(GROUP_A)))))
                .doesNotThrowAnyException();
        assertForbidden(() -> AssignmentGuard.checkAssignable(groupAdmin, List.of(assignment(Role.AGENT, Set.of(), Set.of(GROUP_B)))));
        assertForbidden(() -> AssignmentGuard.checkAssignable(groupAdmin, List.of(assignment(Role.AGENT, Set.of(), Set.of()))));
    }

    @Test
    void oneBadAssignmentRejectsTheWholeRequest() {
        var siteAdmin = caller(Role.ORG_ADMIN, Set.of(SITE_A), Set.of());
        assertForbidden(() -> AssignmentGuard.checkAssignable(siteAdmin, List.of(
                assignment(Role.AGENT, Set.of(SITE_A), Set.of()),
                assignment(Role.AGENT, Set.of(SITE_B), Set.of()))));
    }

    @Test
    void onlyASystemAdministratorMayManageAnotherSystemAdministrator() {
        var orgAdmin = caller(Role.ORG_ADMIN, Set.of(), Set.of());
        var sysAdmin = caller(Role.SYSTEM_ADMIN, Set.of(), Set.of());

        assertForbidden(() -> AssignmentGuard.checkTargetManageable(orgAdmin, Set.of(Role.SYSTEM_ADMIN)));
        assertForbidden(() -> AssignmentGuard.checkTargetManageable(orgAdmin, Set.of(Role.AGENT, Role.SYSTEM_ADMIN)));
        assertThatCode(() -> AssignmentGuard.checkTargetManageable(orgAdmin, Set.of(Role.AGENT))).doesNotThrowAnyException();
        assertThatCode(() -> AssignmentGuard.checkTargetManageable(sysAdmin, Set.of(Role.SYSTEM_ADMIN))).doesNotThrowAnyException();
    }
}
