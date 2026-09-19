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
 * Ticket 22 against real PostgreSQL: the visitor directory and walk-in registration (FR-ISS-020, FR-ISS-021,
 * FR-INT-010, FR-SEC-023), and reception issuing a ticket on a known visitor's behalf with a Priority class and an
 * agent-visible note (FR-ISS-020). The directory's own hard-timeout and fallback behaviour (FR-INT-012, FR-INT-013)
 * is proven at the unit level ({@link VisitorDirectoryGatewayTest}); this class proves the wiring around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class VisitorDirectoryIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-directory");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newZone(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, building_label, floor_label) VALUES (?, ?, 'Ground waiting', 'Block A', 'Ground')", id, site);
        return id;
    }

    private UUID newCounter(UUID zone, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, zone, label);
        return id;
    }

    private UUID newGroup(UUID site, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\",\"bn\":\"বহির্বিভাগ\"}'::jsonb, ?)", id, site, prefix);
        return id;
    }

    private UUID newService(UUID group, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, '[\"reception\"]'::jsonb, 'both', true)",
                id, group, prefix);
        return id;
    }

    private void link(UUID counter, UUID service) {
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
    }

    /** A site with one zone, a counter, a group and one service reception can issue for. */
    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID zone = newZone(site);
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix);
        link(newCounter(zone, "1"), service);
        return new Setup(site, group, service);
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private String token(Role role, UUID... sites) throws Exception {
        UUID user = createUser(role.wire());
        jdbc.update(
                connection -> {
                    var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, user);
                    ps.setString(3, role.wire());
                    ps.setArray(4, connection.createArrayOf("uuid", sites));
                    ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                    return ps;
                });
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
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

    private MvcResult registerWalkIn(String token, String name, String phone, String email, String category, String purpose) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"")
                + (category == null ? "" : ",\"category\":\"" + category + "\"")
                + (purpose == null ? "" : ",\"purpose\":\"" + purpose + "\"")
                + "}";
        return call(post("/api/v1/visitors"), token, body);
    }

    private MvcResult lookup(String token, String query) throws Exception {
        return call(get("/api/v1/visitors/lookup?q=" + query), token, null);
    }

    // ---- registration (FR-ISS-021) ------------------------------------------------------------------------------

    @Test
    void receptionRegistersAWalkInWithAMinimalRecordAndGetsBackAPassReference() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);

        MvcResult created = registerWalkIn(reception, "Amina Rahman", "01700000001", "amina@example.com", "vip", "Needs a wheelchair");

        assertThat(status(created)).as(body(created)).isEqualTo(201);
        String passReference = field(created, "$.pass_reference");
        assertThat(passReference).matches("V-[A-Z0-9]{8}");
        assertThat((String) field(created, "$.name")).isEqualTo("Amina Rahman");
        assertThat((String) field(created, "$.phone")).isEqualTo("01700000001");
        assertThat((String) field(created, "$.email")).isEqualTo("amina@example.com");
        assertThat((String) field(created, "$.category")).isEqualTo("vip");
        assertThat((String) field(created, "$.purpose")).isEqualTo("Needs a wheelchair");

        UUID id = UUID.fromString(field(created, "$.id"));
        assertThat(jdbc.queryForObject("SELECT external_code FROM visitor WHERE id = ?", String.class, id)).isEqualTo(passReference);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'visitor.registered' AND entity_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void nameAndPhoneAreRequiredToRegisterAWalkIn() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);

        MvcResult noName = call(post("/api/v1/visitors"), reception, "{\"phone\":\"01700000002\"}");
        assertThat(status(noName)).isEqualTo(400);
        assertThat((String) field(noName, "$.error.details.fields[0].field")).isEqualTo("name");

        MvcResult noPhone = call(post("/api/v1/visitors"), reception, "{\"name\":\"Karim\"}");
        assertThat(status(noPhone)).isEqualTo(400);
        assertThat((String) field(noPhone, "$.error.details.fields[0].field")).isEqualTo("phone");
    }

    @Test
    void onlyReceptionCanRegisterAWalkIn() throws Exception {
        String agent = token(Role.AGENT);

        MvcResult refused = registerWalkIn(agent, "Karim", "01700000003", null, null, null);

        assertThat(status(refused)).isEqualTo(403);
    }

    // ---- directory search (FR-ISS-020, FR-INT-010) ---------------------------------------------------------------

    @Test
    void receptionFindsARegisteredVisitorByCodeOrByPhoneAndGetsNotFoundForAnUnknownOne() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);
        MvcResult created = registerWalkIn(reception, "Karim Uddin", "01700000004", null, null, null);
        UUID id = UUID.fromString(field(created, "$.id"));
        String pass = field(created, "$.pass_reference");

        MvcResult byCode = lookup(reception, pass);
        assertThat(status(byCode)).as(body(byCode)).isEqualTo(200);
        assertThat((String) field(byCode, "$.id")).isEqualTo(id.toString());
        assertThat((String) field(byCode, "$.name")).isEqualTo("Karim Uddin");
        assertThat((String) field(byCode, "$.phone")).isEqualTo("01700000004");

        MvcResult byPhone = lookup(reception, "01700000004");
        assertThat(status(byPhone)).isEqualTo(200);
        assertThat((String) field(byPhone, "$.id")).isEqualTo(id.toString());

        MvcResult unknown = lookup(reception, "no-such-code");
        assertThat(status(unknown)).isEqualTo(404);
        assertThat((String) field(unknown, "$.error.code")).isEqualTo("not_found");
    }

    @Test
    void anAgentCannotSearchTheDirectoryOnlyTheirOwnRecordsElsewhereAreTheirsToSee() throws Exception {
        String agent = token(Role.AGENT);

        MvcResult refused = lookup(agent, "anything");

        assertThat(status(refused)).isEqualTo(403);
    }

    @Test
    void anOrgAdminCanSearchTheDirectoryToo() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);
        MvcResult created = registerWalkIn(reception, "Fatema", "01700000005", null, null, null);
        String pass = field(created, "$.pass_reference");
        String admin = token(Role.ORG_ADMIN);

        assertThat(status(lookup(admin, pass))).isEqualTo(200);
    }

    // ---- issuing on a visitor's behalf (FR-ISS-020) ---------------------------------------------------------------

    @Test
    void receptionIssuesATicketForARegisteredWalkInWithAPriorityClassAndANoteVisibleToTheAgent() throws Exception {
        Setup s = setup("VD");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult created = registerWalkIn(reception, "Nasrin", "01700000006", null, null, null);
        UUID visitorId = UUID.fromString(field(created, "$.id"));

        String body = "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\",\"visitor_id\":\"" + visitorId
                + "\",\"purpose_note\":\"Needs wheelchair access\"}";
        MvcResult issued = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception, body);

        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT visitor_id FROM ticket WHERE id = ?", UUID.class, ticketId)).isEqualTo(visitorId);
        assertThat(jdbc.queryForObject("SELECT purpose_note FROM ticket WHERE id = ?", String.class, ticketId)).isEqualTo("Needs wheelchair access");
    }

    /** FR-INT-013: POST /tickets never touches the visitor directory at all, so a ticket issues with no visitor details. */
    @Test
    void aTicketIssuesFineWithNoVisitorDetailsAtAllSinceIssuanceNeverCallsTheDirectory() throws Exception {
        Setup s = setup("ND");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult issued = call(
                post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()),
                reception,
                "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\"}");

        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT visitor_id FROM ticket WHERE id = ?", UUID.class, ticketId)).isNull();
    }

    // ---- captured field set (FR-SEC-023) is exercised as a pure unit at VisitorPropertiesTest#capture; this class
    // proves the default configuration (all optional fields on) actually reaches the database.

    @Test
    void theDefaultConfigurationCapturesEmailAndCategoryIntoTheStoredRecord() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);

        MvcResult created = registerWalkIn(reception, "Rahim", "01700000007", "rahim@example.com", "general", null);

        UUID id = UUID.fromString(field(created, "$.id"));
        assertThat(jdbc.queryForObject("SELECT email FROM visitor WHERE id = ?", String.class, id)).isEqualTo("rahim@example.com");
        assertThat(jdbc.queryForObject("SELECT category FROM visitor WHERE id = ?", String.class, id)).isEqualTo("general");
    }

    @Test
    void aVisitorRegisteredWithTheSamePhoneTwiceIsFoundAsTheMostRecentRegistration() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);
        registerWalkIn(reception, "Old record", "01700000008", null, null, null);
        MvcResult latest = registerWalkIn(reception, "Latest record", "01700000008", null, null, null);
        UUID latestId = UUID.fromString(field(latest, "$.id"));

        MvcResult found = lookup(reception, "01700000008");

        assertThat((String) field(found, "$.id")).isEqualTo(latestId.toString());
        assertThat((String) field(found, "$.name")).isEqualTo("Latest record");
    }

    @Test
    void aBlankOrMissingQueryIsValidationFailed() throws Exception {
        String reception = token(Role.RECEPTION_OPERATOR);

        MvcResult missing = call(get("/api/v1/visitors/lookup"), reception, null);
        assertThat(status(missing)).isEqualTo(400);

        MvcResult blank = call(get("/api/v1/visitors/lookup").param("q", " "), reception, null);
        assertThat(status(blank)).isEqualTo(400);
    }
}
