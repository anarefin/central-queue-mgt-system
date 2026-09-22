package com.qms.integration.serviceaccount;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for the {@code service_account} table. Nothing here deletes a row: revoking only flips {@code active}, the
 * same shape {@code device.DeviceRepository} already is for a device credential. */
@Repository
class ServiceAccountRepository {

    private final JdbcTemplate jdbc;

    ServiceAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(String clientId, String secretHash, String label, Set<UUID> siteIds, UUID createdBy, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO service_account (id, client_id, secret_hash, label, site_ids, created_by, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setObject(1, id);
            ps.setString(2, clientId);
            ps.setString(3, secretHash);
            ps.setString(4, label);
            ps.setArray(5, connection.createArrayOf("uuid", siteIds.toArray()));
            ps.setObject(6, createdBy);
            ps.setObject(7, ts(now));
            ps.setObject(8, ts(now));
            return ps;
        });
        return id;
    }

    Optional<ServiceAccount> findByClientId(String clientId) {
        return jdbc.query("SELECT * FROM service_account WHERE client_id = ?", ServiceAccountRepository::map, clientId).stream().findFirst();
    }

    Optional<ServiceAccount> findById(UUID id) {
        return jdbc.query("SELECT * FROM service_account WHERE id = ?", ServiceAccountRepository::map, id).stream().findFirst();
    }

    List<ServiceAccount> all() {
        return jdbc.query("SELECT * FROM service_account ORDER BY created_at, id", ServiceAccountRepository::map);
    }

    void setActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE service_account SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    private static ServiceAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceAccount(
                rs.getObject("id", UUID.class),
                rs.getString("client_id"),
                rs.getString("secret_hash"),
                rs.getString("label"),
                uuids(rs.getArray("site_ids")),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static Set<UUID> uuids(Array array) throws SQLException {
        if (array == null) return Set.of();
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(v -> (UUID) v).collect(Collectors.toUnmodifiableSet());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
