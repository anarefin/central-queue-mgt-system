package com.qms.configuration.site;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** SQL for sites, zones and counters. Nothing here deletes a row: deactivation only flips {@code active} (FR-CFG-001). */
@Repository
class HierarchyRepository {

    private static final String SITE = "id, name, code, timezone, address, default_language, enabled_languages, active, created_at, updated_at";
    private static final String ZONE = "id, site_id, name, building_label, floor_label, display_order, active,"
            + " chime, chime_volume, quiet_start, quiet_end, announcement_languages, max_announce_queue_depth, created_at, updated_at";
    private static final String COUNTER =
            "c.id, c.zone_id, z.site_id, c.label, c.location_note, c.active, c.created_at, c.updated_at FROM counter c JOIN zone z ON z.id = c.zone_id";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    HierarchyRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- sites -------------------------------------------------------------------------------------------------

    List<Site> sites() {
        return jdbc.query("SELECT " + SITE + " FROM site ORDER BY lower(name), id", (rs, i) -> site(rs));
    }

    Optional<Site> site(UUID id) {
        return jdbc.query("SELECT " + SITE + " FROM site WHERE id = ?", (rs, i) -> site(rs), id).stream().findFirst();
    }

    void insert(Site site) {
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages, active, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                site.id(), site.name(), site.code(), site.timezone(), site.address(), site.defaultLanguage(),
                mapper.writeValueAsString(site.enabledLanguages()), site.active(), ts(site.createdAt()), ts(site.updatedAt()));
    }

    void update(Site site) {
        jdbc.update(
                "UPDATE site SET name = ?, code = ?, timezone = ?, address = ?, default_language = ?, enabled_languages = ?::jsonb, updated_at = ? WHERE id = ?",
                site.name(), site.code(), site.timezone(), site.address(), site.defaultLanguage(),
                mapper.writeValueAsString(site.enabledLanguages()), ts(site.updatedAt()), site.id());
    }

    void setSiteActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE site SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    // ---- zones -------------------------------------------------------------------------------------------------

    List<Zone> zonesOfSite(UUID siteId) {
        return jdbc.query("SELECT " + ZONE + " FROM zone WHERE site_id = ? ORDER BY display_order, lower(name), id", (rs, i) -> zone(rs), siteId);
    }

    Optional<Zone> zone(UUID id) {
        return jdbc.query("SELECT " + ZONE + " FROM zone WHERE id = ?", (rs, i) -> zone(rs), id).stream().findFirst();
    }

    void insert(Zone zone) {
        jdbc.update(
                "INSERT INTO zone (id, site_id, name, building_label, floor_label, display_order, active,"
                        + " chime, chime_volume, quiet_start, quiet_end, announcement_languages, max_announce_queue_depth, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                zone.id(), zone.siteId(), zone.name(), zone.buildingLabel(), zone.floorLabel(), zone.displayOrder(), zone.active(),
                zone.chime(), zone.chimeVolume(), zone.quietStart(), zone.quietEnd(), mapper.writeValueAsString(zone.announcementLanguages()),
                zone.maxAnnounceQueueDepth(), ts(zone.createdAt()), ts(zone.updatedAt()));
    }

    void update(Zone zone) {
        jdbc.update(
                "UPDATE zone SET name = ?, building_label = ?, floor_label = ?, display_order = ?,"
                        + " chime = ?, chime_volume = ?, quiet_start = ?, quiet_end = ?, announcement_languages = ?::jsonb, max_announce_queue_depth = ?,"
                        + " updated_at = ? WHERE id = ?",
                zone.name(), zone.buildingLabel(), zone.floorLabel(), zone.displayOrder(),
                zone.chime(), zone.chimeVolume(), zone.quietStart(), zone.quietEnd(), mapper.writeValueAsString(zone.announcementLanguages()),
                zone.maxAnnounceQueueDepth(), ts(zone.updatedAt()), zone.id());
    }

    void setZoneActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE zone SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    List<UUID> activeZoneIdsOfSite(UUID siteId) {
        return jdbc.queryForList("SELECT id FROM zone WHERE site_id = ? AND active ORDER BY id", UUID.class, siteId);
    }

    // ---- counters ----------------------------------------------------------------------------------------------

    List<Counter> countersOfZone(UUID zoneId) {
        return jdbc.query("SELECT " + COUNTER + " WHERE c.zone_id = ? ORDER BY lower(c.label), c.id", (rs, i) -> counter(rs), zoneId);
    }

    Optional<Counter> counter(UUID id) {
        return jdbc.query("SELECT " + COUNTER + " WHERE c.id = ?", (rs, i) -> counter(rs), id).stream().findFirst();
    }

    void insert(Counter counter) {
        jdbc.update(
                "INSERT INTO counter (id, zone_id, label, location_note, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                counter.id(), counter.zoneId(), counter.label(), counter.locationNote(), counter.active(), ts(counter.createdAt()), ts(counter.updatedAt()));
    }

    void update(Counter counter) {
        jdbc.update("UPDATE counter SET label = ?, location_note = ?, updated_at = ? WHERE id = ?",
                counter.label(), counter.locationNote(), ts(counter.updatedAt()), counter.id());
    }

    void setCounterActive(UUID id, boolean active, Instant now) {
        jdbc.update("UPDATE counter SET active = ?, updated_at = ? WHERE id = ?", active, ts(now), id);
    }

    List<UUID> activeCounterIdsOfZone(UUID zoneId) {
        return jdbc.queryForList("SELECT id FROM counter WHERE zone_id = ? AND active ORDER BY id", UUID.class, zoneId);
    }

    // ---- mapping -----------------------------------------------------------------------------------------------

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Site site(ResultSet rs) throws SQLException {
        return new Site(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("timezone"),
                rs.getString("address"),
                rs.getString("default_language"),
                List.copyOf(Arrays.asList(mapper.readValue(rs.getString("enabled_languages"), String[].class))),
                rs.getBoolean("active"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private Zone zone(ResultSet rs) throws SQLException {
        java.sql.Time quietStart = rs.getTime("quiet_start");
        java.sql.Time quietEnd = rs.getTime("quiet_end");
        return new Zone(
                rs.getObject("id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getString("name"),
                rs.getString("building_label"),
                rs.getString("floor_label"),
                rs.getInt("display_order"),
                rs.getBoolean("active"),
                rs.getString("chime"),
                rs.getInt("chime_volume"),
                quietStart == null ? null : quietStart.toLocalTime(),
                quietEnd == null ? null : quietEnd.toLocalTime(),
                List.copyOf(Arrays.asList(mapper.readValue(rs.getString("announcement_languages"), String[].class))),
                rs.getInt("max_announce_queue_depth"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Counter counter(ResultSet rs) throws SQLException {
        return new Counter(
                rs.getObject("id", UUID.class),
                rs.getObject("zone_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getString("label"),
                rs.getString("location_note"),
                rs.getBoolean("active"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
