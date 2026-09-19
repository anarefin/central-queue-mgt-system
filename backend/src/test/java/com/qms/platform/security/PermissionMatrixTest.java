package com.qms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** FR-CFG-101/103/105: the code-defined role → permission sets must equal SRS §5.2, cell by cell. */
class PermissionMatrixTest {

    private static final List<Role> COLUMNS = List.of(
            Role.SYSTEM_ADMIN, Role.ORG_ADMIN, Role.TEAM_ADMIN, Role.AGENT, Role.RECEPTION_OPERATOR);

    /** permission wire name → cells in COLUMNS order. */
    private static Map<String, List<String>> fixture() throws IOException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (var in = PermissionMatrixTest.class.getResourceAsStream("/permission-matrix.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = Arrays.stream(line.split("\\|")).map(String::trim).toArray(String[]::new);
                rows.put(parts[0], List.of(parts).subList(1, parts.length));
            }
        }
        return rows;
    }

    @Test
    void everyCellMatchesTheSrsMatrix() throws IOException {
        List<String> mismatches = new ArrayList<>();
        fixture().forEach((wire, cells) -> {
            Permission permission = Permission.fromWire(wire);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Access expected =
                        switch (cells.get(i)) {
                            case "Y" -> Access.ALLOWED;
                            case "S" -> Access.OWN;
                            default -> Access.DENIED;
                        };
                Access actual = PermissionMatrix.access(COLUMNS.get(i), permission);
                if (actual != expected) {
                    mismatches.add(wire + " / " + COLUMNS.get(i) + ": SRS says " + expected + ", code says " + actual);
                }
            }
        });
        assertThat(mismatches).isEmpty();
    }

    @Test
    void everyPermissionAppearsInTheSrsMatrixExactlyOnce() throws IOException {
        assertThat(fixture().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Permission.values()).map(Permission::wire).toList());
    }

    @Test
    void roleWireNamesAreStableAndRoundTrip() {
        assertThat(Arrays.stream(Role.values()).map(Role::wire))
                .containsExactly("system_admin", "org_admin", "team_admin", "agent", "reception_operator");
        for (Role role : Role.values()) {
            assertThat(Role.fromWire(role.wire())).isEqualTo(role);
        }
        assertThat(Role.tryFromWire("root")).isEmpty();
    }

    @Test
    void authoritiesAreDerivedFromRolesOnlyAndAllowedBeatsOwn() {
        Set<String> agent = PermissionMatrix.authoritiesFor(Set.of(Role.AGENT));
        assertThat(agent).contains("perm:counter_session:open_close:own", "perm:ticket:call_serve_complete:own");
        assertThat(agent).doesNotContain("perm:counter_session:open_close", "perm:user:manage");

        Set<String> both = PermissionMatrix.authoritiesFor(Set.of(Role.AGENT, Role.TEAM_ADMIN));
        assertThat(both).contains("perm:counter_session:open_close");
        assertThat(both).doesNotContain("perm:counter_session:open_close:own");
    }

    @Test
    void effectivePermissionsAreTheUnionOfRoleSets() {
        Set<String> union = PermissionMatrix.authoritiesFor(Set.of(Role.TEAM_ADMIN, Role.RECEPTION_OPERATOR));
        assertThat(union).contains("perm:team_member:request", "perm:ticket:issue");
        assertThat(union).doesNotContain("perm:team_member:approve", "perm:audit:read");
        assertThat(PermissionMatrix.authoritiesFor(Set.of())).isEmpty();
    }

    @Test
    void nothingBeyondSystemAndOrgAdminMayReadTheAuditLogOrManageUsers() {
        for (Role role : Role.values()) {
            boolean admin = role == Role.SYSTEM_ADMIN || role == Role.ORG_ADMIN;
            assertThat(PermissionMatrix.access(role, Permission.AUDIT_READ) == Access.ALLOWED).isEqualTo(admin);
            assertThat(PermissionMatrix.access(role, Permission.USER_MANAGE) == Access.ALLOWED).isEqualTo(admin);
            assertThat(PermissionMatrix.access(role, Permission.ROLE_ASSIGN) == Access.ALLOWED).isEqualTo(admin);
        }
    }

    @Test
    void authorityConstantsUsedInAnnotationsMatchThePermissionEnum() throws IllegalAccessException {
        int seen = 0;
        for (Field field : Authorities.class.getDeclaredFields()) {
            if (!field.getType().equals(String.class)) continue;
            Permission permission = Permission.valueOf(field.getName());
            assertThat(field.get(null)).as(field.getName()).isEqualTo(permission.authority());
            seen++;
        }
        assertThat(seen).isEqualTo(Permission.values().length);
    }
}
