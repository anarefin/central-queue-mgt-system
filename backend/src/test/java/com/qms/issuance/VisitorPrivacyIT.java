package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket 54 against real PostgreSQL: a visitor's own export ({@code GET /visitors/{id}/export}) and deletion
 * ({@code POST /visitors/{id}/anonymize}), both Org Admin only (FR-SEC-031), and a visitor's own consent for
 * retention from their ticket page (FR-SEC-030), the same shape ticket 38's notification opt-out already is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class VisitorPrivacyIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-privacy");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.qms.configuration.privacy.PiiCipher cipher;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private Setup setup() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Ground', 'Ground')", zone, site);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", counter, zone);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\"]'::jsonb, 'both', true)",
                service, group);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
        return new Setup(site, group, service);
    }

    private String token(Role role) throws Exception {
        UUID user = UUID.randomUUID();
        String username = role.wire() + "-" + user;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = call(post("/api/v1/auth/login"), null, "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(status(login)).as(body(login)).isEqualTo(200);
        return field(login, "$.access_token");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Long.class, action);
        return count == null ? 0 : count;
    }

    // ---- export and anonymise (FR-SEC-031) ------------------------------------------------------------------------

    @Test
    void anOrgAdminExportsEverythingHeldAboutOneVisitorThenAnonymisesItAndTheTicketRowSurvives() throws Exception {
        Setup s = setup();
        String reception = token(Role.RECEPTION_OPERATOR);
        String admin = token(Role.ORG_ADMIN);

        MvcResult registered = call(post("/api/v1/visitors"), reception,
                "{\"name\":\"Amina Rahman\",\"phone\":\"01700000010\",\"email\":\"amina@example.com\",\"category\":\"vip\"}");
        assertThat(status(registered)).as(body(registered)).isEqualTo(201);
        UUID visitorId = UUID.fromString(field(registered, "$.id"));

        MvcResult issued = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception,
                "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\",\"visitor_id\":\"" + visitorId
                        + "\",\"purpose_note\":\"Needs wheelchair access\"}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(field(issued, "$.id"));

        // only an Org Admin may export or anonymise
        assertThat(status(call(get("/api/v1/visitors/" + visitorId + "/export"), reception, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/visitors/" + visitorId + "/export"), null, null))).isEqualTo(401);

        long exportsBefore = auditCount("visitor.exported");
        MvcResult exported = call(get("/api/v1/visitors/" + visitorId + "/export"), admin, null);
        assertThat(status(exported)).as(body(exported)).isEqualTo(200);
        assertThat((String) field(exported, "$.name")).isEqualTo("Amina Rahman");
        assertThat((String) field(exported, "$.phone")).isEqualTo("01700000010");
        assertThat((String) field(exported, "$.email")).isEqualTo("amina@example.com");
        assertThat((String) field(exported, "$.category")).isEqualTo("vip");
        assertThat((String) field(exported, "$.tickets[0].id")).isEqualTo(ticketId.toString());
        assertThat((String) field(exported, "$.tickets[0].purpose_note"))
                .as("an export is 'everything held', unlike a default report export FR-SEC-022 excludes notes from")
                .isEqualTo("Needs wheelchair access");
        assertThat(auditCount("visitor.exported")).isEqualTo(exportsBefore + 1);

        MvcResult missing = call(get("/api/v1/visitors/" + UUID.randomUUID() + "/export"), admin, null);
        assertThat(status(missing)).isEqualTo(404);

        long anonymizedBefore = auditCount("visitor.anonymized");
        MvcResult anonymized = call(post("/api/v1/visitors/" + visitorId + "/anonymize"), admin, null);
        assertThat(status(anonymized)).as(body(anonymized)).isEqualTo(200);
        assertThat((Integer) field(anonymized, "$.tickets_anonymized")).isEqualTo(1);
        assertThat((String) field(anonymized, "$.anonymized_at")).isNotNull();
        assertThat(auditCount("visitor.anonymized")).isEqualTo(anonymizedBefore + 1);

        assertThat(jdbc.queryForObject("SELECT name FROM visitor WHERE id = ?", String.class, visitorId)).isNull();
        assertThat(jdbc.queryForObject("SELECT phone FROM visitor WHERE id = ?", String.class, visitorId)).isNull();
        assertThat(jdbc.queryForObject("SELECT email FROM visitor WHERE id = ?", String.class, visitorId)).isNull();
        assertThat(jdbc.queryForObject("SELECT anonymized_at FROM visitor WHERE id = ?", java.sql.Timestamp.class, visitorId)).isNotNull();

        // the ticket row itself survives, with its note cleared, so operational statistics survive (FR-SEC-031)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE id = ?", Integer.class, ticketId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT visitor_id FROM ticket WHERE id = ?", UUID.class, ticketId)).isEqualTo(visitorId);
        assertThat(jdbc.queryForObject("SELECT purpose_note FROM ticket WHERE id = ?", String.class, ticketId)).isNull();

        MvcResult exportedAfter = call(get("/api/v1/visitors/" + visitorId + "/export"), admin, null);
        assertThat((Object) field(exportedAfter, "$.name")).isNull();
        assertThat((String) field(exportedAfter, "$.anonymized_at")).isNotNull();
    }

    // ---- retention consent (FR-SEC-030) --------------------------------------------------------------------------

    @Test
    void aVisitorRecordsTheirOwnConsentForRetentionFromTheirTicketPageAndItIsAudited() throws Exception {
        Setup s = setup();
        String reception = token(Role.RECEPTION_OPERATOR);
        MvcResult registered = call(post("/api/v1/visitors"), reception, "{\"name\":\"Karim\",\"phone\":\"01700000011\"}");
        UUID visitorId = UUID.fromString(field(registered, "$.id"));
        MvcResult issued = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception,
                "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\",\"visitor_id\":\"" + visitorId + "\"}");
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        String secret = field(issued, "$.secret");

        long before = auditCount("retention.consent");
        MvcResult consented = call(
                post("/api/v1/tickets/" + ticketId + "/visitor/retention-consent").header("X-Ticket-Secret", secret), null,
                "{\"granted\":true,\"consent_text_version\":\"2026-09\"}");
        assertThat(status(consented)).as(body(consented)).isEqualTo(200);
        assertThat((Boolean) field(consented, "$.granted")).isTrue();
        assertThat(auditCount("retention.consent")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT granted FROM visitor_retention_consent WHERE visitor_id = ?", Boolean.class, visitorId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT consent_text_version FROM visitor_retention_consent WHERE visitor_id = ?", String.class, visitorId))
                .isEqualTo("2026-09");

        MvcResult revoked = call(
                post("/api/v1/tickets/" + ticketId + "/visitor/retention-consent").header("X-Ticket-Secret", secret), null,
                "{\"granted\":false}");
        assertThat(status(revoked)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT granted FROM visitor_retention_consent WHERE visitor_id = ?", Boolean.class, visitorId)).isFalse();

        MvcResult wrongSecret = call(
                post("/api/v1/tickets/" + ticketId + "/visitor/retention-consent").header("X-Ticket-Secret", "wrong"), null,
                "{\"granted\":true}");
        assertThat(status(wrongSecret)).isEqualTo(401);

        // an org admin's export now carries the visitor's own retention consent record too
        String admin = token(Role.ORG_ADMIN);
        MvcResult exported = call(get("/api/v1/visitors/" + visitorId + "/export"), admin, null);
        assertThat((Boolean) field(exported, "$.retention_consent.granted")).isFalse();
        // The revoke call above sent no consent_text_version, so it fell back to the default rather than keeping
        // the version of the earlier grant.
        assertThat((String) field(exported, "$.retention_consent.consent_text_version")).isEqualTo("v1");
    }
}
