package com.qms.configuration.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Ticket 54 against real PostgreSQL (SRS §25.3, FR-SEC-020, FR-SEC-023): {@code /privacy/field-config}, and its two
 * real effects — the kiosk confirmation screen (FR-ISS-013) and what a walk-in registration captures at all
 * (FR-ISS-021).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class VisitorFieldConfigIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-privacy-field-config");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        // Every test starts from the migration's own defaults, whichever test ran last.
        jdbc.update("UPDATE visitor_field_config SET visible = true, updated_at = now(), updated_by = NULL");
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private String tokenFor(Role role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
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

    // ---- the admin screen (FR-SEC-020, FR-SEC-023) ------------------------------------------------------------

    @Test
    void theDefaultsMatchSec3AndOnlySystemOrOrgAdminMayViewOrChangeThem() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        String teamAdmin = tokenFor(Role.TEAM_ADMIN);

        MvcResult capture = call(get("/api/v1/privacy/field-config/capture"), admin, null);
        assertThat(status(capture)).as(body(capture)).isEqualTo(200);
        List<Map<String, Object>> captureFields = field(capture, "$.items");
        assertThat(captureFields).extracting(m -> m.get("field")).containsExactlyInAnyOrder("email", "category", "purpose");
        assertThat(captureFields).allSatisfy(m -> assertThat((Boolean) m.get("visible")).isTrue());

        MvcResult kiosk = call(get("/api/v1/privacy/field-config/kiosk_confirmation"), admin, null);
        List<Map<String, Object>> kioskFields = field(kiosk, "$.items");
        assertThat(kioskFields).extracting(m -> m.get("field")).containsExactlyInAnyOrder("name", "category");

        assertThat(status(call(get("/api/v1/privacy/field-config/capture"), teamAdmin, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/privacy/field-config/capture"), null, null))).isEqualTo(401);
        assertThat(status(call(put("/api/v1/privacy/field-config/capture/email"), teamAdmin, "{\"visible\":false}"))).isEqualTo(403);

        assertThat(status(call(get("/api/v1/privacy/field-config/no-such-surface"), admin, null))).isEqualTo(404);
    }

    @Test
    void updatingAFieldValidatesItAndIsAudited() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        long before = auditCount("privacy.field_config_changed");

        assertThat(status(call(put("/api/v1/privacy/field-config/capture/phone"), admin, "{\"visible\":false}")))
                .as("phone is always captured; it is not one of capture's own configurable fields").isEqualTo(400);
        assertThat(status(call(put("/api/v1/privacy/field-config/capture/email"), admin, "{}")))
                .as("visible is required").isEqualTo(400);

        MvcResult updated = call(put("/api/v1/privacy/field-config/capture/email"), admin, "{\"visible\":false}");
        assertThat(status(updated)).as(body(updated)).isEqualTo(200);
        assertThat((Boolean) field(updated, "$.visible")).isFalse();
        assertThat(auditCount("privacy.field_config_changed")).isEqualTo(before + 1);

        MvcResult listed = call(get("/api/v1/privacy/field-config/capture"), admin, null);
        List<Map<String, Object>> items = field(listed, "$.items");
        assertThat(items.stream().filter(m -> "email".equals(m.get("field"))).findFirst().orElseThrow().get("visible")).isEqualTo(false);
    }

    // ---- effect on the kiosk confirmation screen (FR-ISS-013, FR-ISS-014) ------------------------------------------

    @Test
    void turningOffAKioskConfirmationFieldDropsItFromTheKioskIdentifyResponse() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID visitor = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, 'Farhana Akter', 'vip', now())",
                visitor, "KFC-" + visitor.toString().substring(0, 8));
        UUID site = createSite(admin);
        String kioskToken = pairKiosk(admin, site);

        MvcResult before = call(get("/api/v1/kiosk/visitors/identify").param("q", "KFC-" + visitor.toString().substring(0, 8)), kioskToken, null);
        assertThat(status(before)).as(body(before)).isEqualTo(200);
        assertThat((String) field(before, "$.name")).isEqualTo("Farhana Akter");
        assertThat((String) field(before, "$.category")).isEqualTo("vip");

        call(put("/api/v1/privacy/field-config/kiosk_confirmation/category"), admin, "{\"visible\":false}");

        MvcResult after = call(get("/api/v1/kiosk/visitors/identify").param("q", "KFC-" + visitor.toString().substring(0, 8)), kioskToken, null);
        assertThat((String) field(after, "$.name")).isEqualTo("Farhana Akter");
        assertThat(body(after)).as("turned off, so the real category value never reaches the kiosk").doesNotContain("vip");
        assertThat((Object) field(after, "$.category")).isNull();
    }

    // ---- effect on walk-in registration capture (FR-SEC-023) ---------------------------------------------------

    @Test
    void turningOffCaptureOfAFieldMeansItIsNeitherCapturedNorRetained() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        String reception = tokenFor(Role.RECEPTION_OPERATOR);
        call(put("/api/v1/privacy/field-config/capture/email"), admin, "{\"visible\":false}");

        MvcResult registered = call(post("/api/v1/visitors"), reception,
                "{\"name\":\"Nasrin\",\"phone\":\"01700000099\",\"email\":\"nasrin@example.com\",\"category\":\"general\"}");
        assertThat(status(registered)).as(body(registered)).isEqualTo(201);
        assertThat(body(registered)).as("turned off, so absent from the response").doesNotContain("nasrin@example.com");

        UUID id = UUID.fromString(field(registered, "$.id"));
        assertThat(jdbc.queryForObject("SELECT email FROM visitor WHERE id = ?", String.class, id))
                .as("and never reached the insert, so it is not retained either").isNull();
        assertThat(jdbc.queryForObject("SELECT category FROM visitor WHERE id = ?", String.class, id))
                .as("category was left on, so it is still captured").isEqualTo("general");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private UUID createSite(String token) throws Exception {
        MvcResult created = call(post("/api/v1/sites"), token,
                "{\"name\":\"Main campus\",\"code\":\"FC-" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"timezone\":\"Asia/Dhaka\",\"address\":\"1 Campus Road\",\"default_language\":\"en\",\"enabled_languages\":[\"en\"]}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private String pairKiosk(String token, UUID site) throws Exception {
        MvcResult code = call(post("/api/v1/devices/pairing-codes"), token,
                "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"label\":\"Front desk\"}");
        assertThat(status(code)).as(body(code)).isEqualTo(201);
        MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + field(code, "$.code") + "\"}");
        assertThat(status(paired)).as(body(paired)).isEqualTo(201);
        return field(paired, "$.access_token");
    }
}
