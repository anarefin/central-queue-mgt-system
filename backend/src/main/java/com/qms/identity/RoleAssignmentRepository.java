package com.qms.identity;

import com.qms.platform.security.Role;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RoleAssignmentRepository {

    static final String MANUAL = "manual";
    static final String EXTERNAL = "external";

    private final JdbcTemplate jdbc;

    RoleAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RoleAssignment> findByUser(UUID userId) {
        List<RoleAssignment> found = new ArrayList<>();
        jdbc.query(
                "SELECT role, site_ids, group_ids FROM role_assignments WHERE user_id = ? ORDER BY created_at, id",
                rs -> {
                    Optional<Role> role = Role.tryFromWire(rs.getString("role"));
                    if (role.isPresent()) { // a role removed from the code is ignored, never granted
                        found.add(new RoleAssignment(role.get(), uuids(rs.getArray("site_ids")), uuids(rs.getArray("group_ids"))));
                    }
                },
                userId);
        return found;
    }

    /** Replaces the manually assigned roles of a user. Assignments owned by an external mapping are left alone. */
    void replaceAll(UUID userId, List<RoleAssignment> assignments) {
        replace(userId, assignments, MANUAL);
    }

    /** Replaces the assignments an external group mapping owns (FR-INT-002), leaving manual ones untouched. */
    void replaceExternal(UUID userId, List<RoleAssignment> assignments) {
        replace(userId, assignments, EXTERNAL);
    }

    private void replace(UUID userId, List<RoleAssignment> assignments, String source) {
        jdbc.update("DELETE FROM role_assignments WHERE user_id = ? AND source = ?", userId, source);
        for (RoleAssignment assignment : assignments) {
            jdbc.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids, source) VALUES (?, ?, ?, ?, ?, ?)");
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, userId);
                ps.setString(3, assignment.role().wire());
                ps.setArray(4, connection.createArrayOf("uuid", assignment.siteIds().toArray()));
                ps.setArray(5, connection.createArrayOf("uuid", assignment.groupIds().toArray()));
                ps.setString(6, source);
                return ps;
            });
        }
    }

    private static Set<UUID> uuids(java.sql.Array array) throws SQLException {
        if (array == null) return Set.of();
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(v -> (UUID) v).collect(Collectors.toUnmodifiableSet());
    }
}
