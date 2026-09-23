package com.qms.configuration.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Branding and the printed-token template over HTTP against real PostgreSQL (ticket 27, SRS §7.5): defaults before
 * any admin has ever saved, persistence and audit of a real change, that a no-op writes nothing, validation, the
 * permission check, and that a paired kiosk's own bootstrap picks up whatever an admin last saved without another
 * endpoint (FR-CFG-030..032, FR-SEC-020, FR-SEC-040). Also the public, unauthenticated theme read a login screen or
 * the anonymous visitor page themes itself with, and its per-IP rate limit (ticket 62).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class BrandingAdminIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-branding");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---- helpers -----------------------------------------------------------------------------------------------

    private String tokenFor(Role role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), username, "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.access_token");
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
        return result.getResponse().getContentAsString();
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    /** {@code org_branding} and {@code print_template} are organisation-wide singletons every test method shares
     * (same Postgres container, same Spring context, no reset between methods): a suffix keeps one test's "new"
     * value from accidentally matching another's and being treated as the no-op it is not. */
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int count(String table, String where, Object... args) {
        Integer result = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
        return result == null ? 0 : result;
    }

    private UUID createSite(String token) throws Exception {
        String json = "{\"name\":\"Main campus\",\"code\":\"S-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"timezone\":\"Asia/Dhaka\","
                + "\"address\":\"1 Campus Road\",\"default_language\":\"en\",\"enabled_languages\":[\"en\"]}";
        MvcResult created = call(post("/api/v1/sites"), token, json);
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private String pairingCode(String token, UUID site) throws Exception {
        MvcResult created = call(post("/api/v1/devices/pairing-codes"), token,
                "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"zone_id\":null,\"label\":\"Front desk\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return field(created, "$.code");
    }

    private String pairedKioskToken(String admin, UUID site) throws Exception {
        MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + pairingCode(admin, site) + "\"}");
        assertThat(status(paired)).as(body(paired)).isEqualTo(201);
        return field(paired, "$.access_token");
    }

    // ---- defaults (FR-SEC-020's printed-token row, FR-CFG-030) ---------------------------------------------------

    @Test
    void beforeAnyAdminHasSavedTheDefaultsApply() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);

        MvcResult branding = call(get("/api/v1/branding"), admin, null);
        assertThat(status(branding)).as(body(branding)).isEqualTo(200);
        assertThat((String) field(branding, "$.org_name")).isNotBlank();
        assertThat((String) field(branding, "$.primary_color")).matches("^#[0-9a-fA-F]{6}$");
        assertThat((Object) field(branding, "$.logo_url")).isNull();

        MvcResult template = call(get("/api/v1/print-template"), admin, null);
        assertThat(status(template)).as(body(template)).isEqualTo(200);
        // FR-SEC-020's "printed token" row: token, floor, service group, code, name, category, time.
        assertThat((List<String>) field(template, "$.fields"))
                .containsExactly("token_number", "floor", "service_group", "visitor_code", "visitor_name", "visitor_category", "issue_time");
    }

    // ---- persistence and audit (FR-CFG-030..031, FR-SEC-040) ------------------------------------------------------

    @Test
    void savingBrandingPersistsAndWritesOneAuditEntryAndANoOpWritesNothing() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        String orgName = unique("Northside Clinic");
        int before = count("audit_log", "action = 'branding.updated'");

        MvcResult saved = call(put("/api/v1/branding"), admin,
                "{\"org_name\":\"" + orgName + "\",\"primary_color\":\"#123ABC\",\"logo_url\":\"https://example.org/logo.png\"}");
        assertThat(status(saved)).as(body(saved)).isEqualTo(200);
        assertThat((String) field(saved, "$.org_name")).isEqualTo(orgName);
        assertThat((String) field(saved, "$.primary_color")).isEqualTo("#123ABC");
        assertThat((String) field(saved, "$.logo_url")).isEqualTo("https://example.org/logo.png");
        assertThat(jdbc.queryForObject("SELECT org_name FROM org_branding WHERE id = true", String.class)).isEqualTo(orgName);
        assertThat(count("audit_log", "action = 'branding.updated'")).isEqualTo(before + 1);

        // saving the exact same values again writes nothing more
        call(put("/api/v1/branding"), admin,
                "{\"org_name\":\"" + orgName + "\",\"primary_color\":\"#123ABC\",\"logo_url\":\"https://example.org/logo.png\"}");
        assertThat(count("audit_log", "action = 'branding.updated'")).isEqualTo(before + 1);
    }

    @Test
    void savingThePrintTemplatePersistsAndWritesAnAuditEntry() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        String noticeLine = unique("Please keep this token safe.");
        int before = count("audit_log", "action = 'print_template.updated'");

        MvcResult saved = call(put("/api/v1/print-template"), admin,
                "{\"fields\":[\"token_number\",\"qr_code\",\"notice_line\"],\"notice_line\":\"" + noticeLine + "\"}");
        assertThat(status(saved)).as(body(saved)).isEqualTo(200);
        assertThat((List<String>) field(saved, "$.fields")).containsExactly("token_number", "qr_code", "notice_line");
        assertThat((String) field(saved, "$.notice_line")).isEqualTo(noticeLine);
        assertThat(count("audit_log", "action = 'print_template.updated'")).isEqualTo(before + 1);
    }

    // ---- validation (FR-CFG-031) ------------------------------------------------------------------------------

    @Test
    void malformedBrandingAndTemplateAreValidationFailedNamingTheField() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);

        assertThat((String) field(
                        call(put("/api/v1/branding"), admin, "{\"org_name\":\"\",\"primary_color\":\"#123ABC\"}"), "$.error.details.fields[0].field"))
                .isEqualTo("org_name");
        assertThat((String) field(
                        call(put("/api/v1/branding"), admin, "{\"org_name\":\"Clinic\",\"primary_color\":\"blue\"}"), "$.error.details.fields[0].field"))
                .isEqualTo("primary_color");
        assertThat((String) field(call(put("/api/v1/print-template"), admin, "{\"fields\":[]}"), "$.error.details.fields[0].field"))
                .isEqualTo("fields");
        assertThat((String) field(
                        call(put("/api/v1/print-template"), admin, "{\"fields\":[\"carrier_pigeon\"]}"), "$.error.details.fields[0].field"))
                .isEqualTo("fields");
    }

    // ---- permission check (API-016) --------------------------------------------------------------------------

    @Test
    void onlyAnOrgOrSystemAdminMayReadOrChangeBrandingOrTheTemplate() throws Exception {
        String agent = tokenFor(Role.AGENT);

        assertThat(status(call(get("/api/v1/branding"), agent, null))).isEqualTo(403);
        assertThat(status(call(put("/api/v1/branding"), agent, "{\"org_name\":\"x\",\"primary_color\":\"#000000\"}"))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/print-template"), agent, null))).isEqualTo(403);
        assertThat(status(call(put("/api/v1/print-template"), agent, "{\"fields\":[\"token_number\"]}"))).isEqualTo(403);
    }

    // ---- applied everywhere without another round trip (FR-CFG-030..032) ------------------------------------------

    @Test
    void aPairedKiosksBootstrapReflectsWhateverBrandingAndTemplateAnAdminLastSaved() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        String kiosk = pairedKioskToken(admin, site);

        call(put("/api/v1/branding"), admin, "{\"org_name\":\"Northside Clinic\",\"primary_color\":\"#123ABC\",\"logo_url\":\"https://example.org/logo.png\"}");
        call(put("/api/v1/print-template"), admin, "{\"fields\":[\"token_number\",\"qr_code\"],\"notice_line\":\"Keep this token.\"}");

        MvcResult bootstrap = call(get("/api/v1/config/bootstrap"), kiosk, null);
        assertThat(status(bootstrap)).as(body(bootstrap)).isEqualTo(200);
        assertThat((String) field(bootstrap, "$.branding.org_name")).isEqualTo("Northside Clinic");
        assertThat((String) field(bootstrap, "$.branding.primary_color")).isEqualTo("#123ABC");
        assertThat((String) field(bootstrap, "$.branding.logo_url")).isEqualTo("https://example.org/logo.png");
        assertThat((List<String>) field(bootstrap, "$.print_template.fields")).containsExactly("token_number", "qr_code");
        assertThat((String) field(bootstrap, "$.print_template.notice_line")).isEqualTo("Keep this token.");

        // the device-only bootstrap credential cannot reach the staff-only branding endpoints, and vice versa
        assertThat(status(call(get("/api/v1/branding"), kiosk, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/config/bootstrap"), admin, null))).isEqualTo(403);
    }

    // ---- public theme read (FR-CFG-030, ticket 62) -----------------------------------------------------------------

    /**
     * One test method so it is the only caller of this endpoint in the class: the rate limiter's counter is shared
     * across the whole Spring context, and a second test calling {@code GET /branding/theme} would make the final
     * assertion here order-dependent.
     */
    @Test
    void theThemeIsPublicReflectsSavedBrandingAndIsRateLimited() throws Exception {
        MvcResult before = mvc.perform(get("/api/v1/branding/theme")).andReturn();
        assertThat(status(before)).as(body(before)).isEqualTo(200);
        assertThat((String) field(before, "$.org_name")).isNotBlank();
        assertThat(body(before)).doesNotContain("updated_at").doesNotContain("updated_by");

        String admin = tokenFor(Role.ORG_ADMIN);
        String orgName = unique("Riverside Clinic");
        call(put("/api/v1/branding"), admin, "{\"org_name\":\"" + orgName + "\",\"primary_color\":\"#123ABC\",\"logo_url\":\"https://example.org/logo.png\"}");

        MvcResult after = mvc.perform(get("/api/v1/branding/theme")).andReturn();
        assertThat(status(after)).as(body(after)).isEqualTo(200);
        assertThat((String) field(after, "$.org_name")).isEqualTo(orgName);
        assertThat((String) field(after, "$.primary_color")).isEqualTo("#123ABC");
        assertThat((String) field(after, "$.logo_url")).isEqualTo("https://example.org/logo.png");

        // 2 calls already made above; BrandingController allows 30/minute/IP before refusing the 31st.
        for (int i = 0; i < 28; i++) {
            MvcResult ok = mvc.perform(get("/api/v1/branding/theme")).andReturn();
            assertThat(status(ok)).as(body(ok)).isEqualTo(200);
        }
        MvcResult limited = mvc.perform(get("/api/v1/branding/theme")).andReturn();
        assertThat(status(limited)).as(body(limited)).isEqualTo(429);
        assertThat((String) field(limited, "$.error.code")).isEqualTo("rate_limited");
    }
}
