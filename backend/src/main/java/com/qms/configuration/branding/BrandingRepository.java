package com.qms.configuration.branding;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The single, organisation-wide {@code org_branding} row (id is always {@code true}, FR-CFG-030). */
@Repository
class BrandingRepository {

    private final JdbcTemplate jdbc;

    BrandingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@link OrgBranding#DEFAULT}'s values would never actually surface: V22 seeds the row on every install. */
    OrgBranding get() {
        List<OrgBranding> rows = jdbc.query(
                "SELECT org_name, primary_color, logo_url, updated_at, updated_by FROM org_branding WHERE id = true",
                (rs, i) -> new OrgBranding(
                        rs.getString("org_name"),
                        rs.getString("primary_color"),
                        rs.getString("logo_url"),
                        rs.getObject("updated_at", OffsetDateTime.class) == null ? null : rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_by", UUID.class)));
        return rows.isEmpty() ? OrgBranding.DEFAULT : rows.get(0);
    }

    void save(OrgBranding branding, UUID updatedBy, Instant now) {
        jdbc.update(
                "INSERT INTO org_branding (id, org_name, primary_color, logo_url, updated_at, updated_by) VALUES (true, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET org_name = EXCLUDED.org_name, primary_color = EXCLUDED.primary_color, "
                        + "logo_url = EXCLUDED.logo_url, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
                branding.orgName(), branding.primaryColor(), branding.logoUrl(), ts(now), updatedBy);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
