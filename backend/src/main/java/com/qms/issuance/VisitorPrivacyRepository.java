package com.qms.issuance;

import com.qms.configuration.privacy.PiiCipher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The reads and the anonymising write FR-SEC-031 (ticket 54) needs: everything the {@code visitor} row itself and its own tickets hold. */
@Repository
class VisitorPrivacyRepository {

    record VisitorRecord(
            UUID id,
            String externalCode,
            String name,
            String category,
            String phone,
            String email,
            String preferredLanguage,
            Instant createdAt,
            Instant anonymizedAt) {}

    record TicketRecord(
            UUID id,
            String tokenNumber,
            String state,
            Map<String, String> serviceNames,
            UUID siteId,
            String purposeNote,
            Instant issuedAt,
            Instant closedAt) {}

    record ConsentRecord(boolean granted, String consentTextVersion, Instant recordedAt) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final PiiCipher cipher;

    VisitorPrivacyRepository(JdbcTemplate jdbc, JsonMapper mapper, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    Optional<VisitorRecord> find(UUID id) {
        return jdbc.query(
                        "SELECT id, external_code, name, category, phone, email, preferred_language, created_at, anonymized_at FROM visitor WHERE id = ?",
                        (rs, i) -> new VisitorRecord(
                                rs.getObject("id", UUID.class),
                                rs.getString("external_code"),
                                rs.getString("name"),
                                rs.getString("category"),
                                rs.getString("phone"),
                                rs.getString("email"),
                                rs.getString("preferred_language"),
                                instant(rs.getObject("created_at", OffsetDateTime.class)),
                                instant(rs.getObject("anonymized_at", OffsetDateTime.class))),
                        id)
                .stream()
                .findFirst();
    }

    List<TicketRecord> ticketsOf(UUID visitorId) {
        return jdbc.query(
                "SELECT t.id, t.token_number, t.state, v.name_i18n AS service_names, t.site_id, t.purpose_note, t.issued_at, t.closed_at"
                        + " FROM ticket t JOIN service v ON v.id = t.service_id WHERE t.visitor_id = ? ORDER BY t.issued_at",
                (rs, i) -> new TicketRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("token_number"),
                        rs.getString("state"),
                        names(rs.getString("service_names")),
                        rs.getObject("site_id", UUID.class),
                        cipher.decrypt(rs.getString("purpose_note")),
                        instant(rs.getObject("issued_at", OffsetDateTime.class)),
                        instant(rs.getObject("closed_at", OffsetDateTime.class))),
                visitorId);
    }

    Optional<ConsentRecord> notificationConsent(UUID visitorId) {
        return jdbc.query(
                        "SELECT opted_out, consent_text_version, recorded_at FROM notification_consent WHERE visitor_id = ?",
                        (rs, i) -> new ConsentRecord(
                                !rs.getBoolean("opted_out"), rs.getString("consent_text_version"), instant(rs.getObject("recorded_at", OffsetDateTime.class))),
                        visitorId)
                .stream()
                .findFirst();
    }

    Optional<ConsentRecord> retentionConsent(UUID visitorId) {
        return jdbc.query(
                        "SELECT granted, consent_text_version, recorded_at FROM visitor_retention_consent WHERE visitor_id = ?",
                        (rs, i) -> new ConsentRecord(
                                rs.getBoolean("granted"), rs.getString("consent_text_version"), instant(rs.getObject("recorded_at", OffsetDateTime.class))),
                        visitorId)
                .stream()
                .findFirst();
    }

    /**
     * FR-SEC-031: anonymises rather than deletes. The visitor's own identifying fields are cleared and {@code
     * anonymized_at} set; every one of their tickets keeps its row (state, timestamps, Service, Site — everything
     * operational statistics need) but loses its free-text note, the one other PII-bearing field a ticket carries.
     * Returns how many ticket rows were touched, for the audit entry (never the note text itself).
     */
    int anonymize(UUID visitorId, Instant now) {
        jdbc.update(
                "UPDATE visitor SET external_code = NULL, name = NULL, category = NULL, phone = NULL, email = NULL, preferred_language = NULL, anonymized_at = ?"
                        + " WHERE id = ?",
                ts(now), visitorId);
        return jdbc.update("UPDATE ticket SET purpose_note = NULL WHERE visitor_id = ? AND purpose_note IS NOT NULL", visitorId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> names(String json) {
        return json == null ? Map.of() : mapper.readValue(json, Map.class);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
