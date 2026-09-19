package com.qms.configuration.catalogue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for teams and their members. A team is created with its service group and never deleted. */
@Repository
class TeamRepository {

    /** The bare team row, without members. */
    record TeamRow(UUID id, UUID serviceGroupId, String name) {}

    private final JdbcTemplate jdbc;

    TeamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID serviceGroupId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, ?)", id, serviceGroupId, name);
        return id;
    }

    void rename(UUID serviceGroupId, String name) {
        jdbc.update("UPDATE team SET name = ? WHERE service_group_id = ?", name, serviceGroupId);
    }

    Optional<TeamRow> ofGroup(UUID serviceGroupId) {
        return jdbc.query(
                        "SELECT id, service_group_id, name FROM team WHERE service_group_id = ?",
                        (rs, i) -> new TeamRow(rs.getObject("id", UUID.class), rs.getObject("service_group_id", UUID.class), rs.getString("name")),
                        serviceGroupId)
                .stream().findFirst();
    }

    /** The site a service group belongs to, so a caller's site scope can be checked. */
    Optional<UUID> siteOfGroup(UUID serviceGroupId) {
        return jdbc.queryForList("SELECT site_id FROM service_group WHERE id = ?", UUID.class, serviceGroupId).stream().findFirst();
    }

    List<Team.Member> members(UUID teamId) {
        return jdbc.query(
                "SELECT m.user_id, u.username, u.display_name, u.active, m.added_at FROM team_member m JOIN users u ON u.id = m.user_id"
                        + " WHERE m.team_id = ? ORDER BY lower(coalesce(u.display_name, u.username)), m.user_id",
                (rs, i) -> new Team.Member(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getBoolean("active"),
                        rs.getObject("added_at", OffsetDateTime.class).toInstant()),
                teamId);
    }

    /** True if the user was added, false if they were already a member. */
    boolean addMember(UUID teamId, UUID userId, UUID addedBy) {
        return jdbc.update("INSERT INTO team_member (team_id, user_id, added_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", teamId, userId, addedBy) > 0;
    }

    boolean removeMember(UUID teamId, UUID userId) {
        return jdbc.update("DELETE FROM team_member WHERE team_id = ? AND user_id = ?", teamId, userId) > 0;
    }

    /** {@code empty} when there is no such user, otherwise whether the account is enabled. */
    Optional<Boolean> userActive(UUID userId) {
        return jdbc.queryForList("SELECT active FROM users WHERE id = ?", Boolean.class, userId).stream().findFirst();
    }
}
