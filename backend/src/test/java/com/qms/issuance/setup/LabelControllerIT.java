package com.qms.issuance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * Ticket 69 against real PostgreSQL: the parts of terminology remapping {@link SetupWizardIT} does not already
 * cover — {@code GET /labels/public} (anonymous, entity-only), a bootstrap-served copy for kiosk/display devices,
 * a reset back to the pack default, value validation, and both writes being audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, LabelControllerIT.Clocks.class})
class LabelControllerIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-22T09:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.now();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-labels");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main branch', ?, 'Asia/Dhaka', '1 Main Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    @Test
    void aProfileSeededLabelIsPublicResetableAndAuditedBothWays() throws Exception {
        UUID site = newSite();
        String admin = user(Role.SYSTEM_ADMIN, site);

        // §3.4/§3.2: applying banking seeds entity.visitor = "Customer".
        MvcResult applied = call(post("/api/v1/setup/profile"), admin, "{\"profile_id\":\"banking\"}");
        assertThat(status(applied)).as(body(applied)).isEqualTo(200);

        // The public, anonymous read: only the seven entity.* keys, no Authorization header.
        MvcResult pub = mvc.perform(get("/api/v1/labels/public?lang=en")).andReturn();
        assertThat(status(pub)).as(body(pub)).isEqualTo(200);
        assertThat(str(pub, "$['entity.visitor']")).isEqualTo("Customer");
        assertThat(str(pub, "$['entity.ticket']")).isEqualTo("Token");

        // A staff-only key (were one ever added) must never leak through the public endpoint: today only entity.*
        // rows exist, so the guarantee is that the query is scoped by key prefix, not by an incidental empty table.
        Integer nonEntityLeaked = jdbc.queryForObject(
                "SELECT count(*) FROM label_override WHERE lang = 'en' AND key NOT LIKE 'entity.%'", Integer.class);
        assertThat(nonEntityLeaked).isZero();

        // Validation: required, at most 60 characters (ticket 69).
        MvcResult blank = call(put("/api/v1/labels/entity.visitor"), admin, "{\"lang\":\"en\",\"value\":\"  \"}");
        assertThat(status(blank)).isEqualTo(400);
        assertThat(str(blank, "$.error.details.fields[0].field")).isEqualTo("value");

        MvcResult tooLong = call(put("/api/v1/labels/entity.visitor"), admin, "{\"lang\":\"en\",\"value\":\"" + "x".repeat(61) + "\"}");
        assertThat(status(tooLong)).isEqualTo(400);

        // A valid edit is accepted, resolves immediately, and is audited (verifies/adds the PUT audit, ticket 69).
        MvcResult edited = call(put("/api/v1/labels/entity.visitor"), admin, "{\"lang\":\"en\",\"value\":\"Client\"}");
        assertThat(status(edited)).as(body(edited)).isEqualTo(200);
        assertThat(str(edited, "$['entity.visitor']")).isEqualTo("Client");

        String updatedAfter = jdbc.queryForObject(
                "SELECT (after->>'value') FROM audit_log WHERE action = 'label.updated' AND after->>'key' = 'entity.visitor' ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        assertThat(updatedAfter).isEqualTo("Client");

        // A device's bootstrap carries the Site's own resolved copy, refreshed the same way feature flags already
        // are on config.changed — no second endpoint for a kiosk or display to call.
        String kiosk = pairDevice("kiosk", admin, site);
        MvcResult bootstrap = call(get("/api/v1/config/bootstrap"), kiosk, null);
        assertThat(status(bootstrap)).as(body(bootstrap)).isEqualTo(200);
        assertThat(str(bootstrap, "$.labels['entity.visitor']")).isEqualTo("Client");

        // Reset (DELETE /labels/{key}?lang=): falls back to the pack's own default noun, i.e. the key disappears
        // from the resolved map entirely, and is audited as label.reset (ticket 69).
        MvcResult reset = call(delete("/api/v1/labels/entity.visitor?lang=en"), admin, null);
        assertThat(status(reset)).as(body(reset)).isEqualTo(200);
        Map<String, Object> afterReset = JsonPath.read(body(reset), "$");
        assertThat(afterReset).doesNotContainKey("entity.visitor");

        String resetBefore = jdbc.queryForObject(
                "SELECT (before->>'value') FROM audit_log WHERE action = 'label.reset' AND before->>'key' = 'entity.visitor' ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        assertThat(resetBefore).isEqualTo("Client");

        // The device's next bootstrap (as if refetched on config.changed) no longer carries the reset key.
        MvcResult bootstrapAfterReset = call(get("/api/v1/config/bootstrap"), kiosk, null);
        Map<String, Object> labelsAfterReset = JsonPath.read(body(bootstrapAfterReset), "$.labels");
        assertThat(labelsAfterReset).doesNotContainKey("entity.visitor");
    }

    @Test
    void writingAndResettingALabelNeedsConfigWritePermissionUnlikeReadingIt() throws Exception {
        UUID site = newSite();
        String admin = user(Role.SYSTEM_ADMIN, site);
        String agent = user(Role.AGENT, site);

        // Reading needs no config:org_sites_zones (the same reach GET /setup/feature-flags already has).
        MvcResult read = call(get("/api/v1/labels?lang=en"), agent, null);
        assertThat(status(read)).as(body(read)).isEqualTo(200);

        // label_override is an organisation-wide singleton table with no Site scoping, shared by every test in this
        // class (the same reason SetupWizardIT keeps its own such scenario to one long method); this test uses
        // entity.counter rather than entity.visitor so it cannot race the other test's own edits to that key.
        MvcResult write = call(put("/api/v1/labels/entity.counter"), agent, "{\"lang\":\"en\",\"value\":\"Nope\"}");
        assertThat(status(write)).isEqualTo(403);

        MvcResult resetForbidden = call(delete("/api/v1/labels/entity.counter?lang=en"), agent, null);
        assertThat(status(resetForbidden)).isEqualTo(403);

        // Sanity: the admin can still do both.
        MvcResult adminWrite = call(put("/api/v1/labels/entity.counter"), admin, "{\"lang\":\"en\",\"value\":\"Desk\"}");
        assertThat(status(adminWrite)).as(body(adminWrite)).isEqualTo(200);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private String user(Role role, UUID site) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, 'en')",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire());
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        Instant businessTime = clock.instant();
        clock.set(Instant.now());
        MvcResult result;
        try {
            result = mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
        } finally {
            clock.set(businessTime);
        }
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        return field(result, "$.access_token");
    }

    /** Minted at real time whatever the test clock says, exactly like {@link #user}. */
    private String pairDevice(String kind, String admin, UUID site) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            MvcResult code = call(post("/api/v1/devices/pairing-codes"), admin, "{\"kind\":\"" + kind + "\",\"site_id\":\"" + site + "\",\"label\":\"D\"}");
            assertThat(status(code)).as(body(code)).isEqualTo(201);
            MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + field(code, "$.code") + "\"}");
            assertThat(status(paired)).as(body(paired)).isEqualTo(201);
            return field(paired, "$.access_token");
        } finally {
            clock.set(testTime);
        }
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

    private static String str(MvcResult result, String path) throws Exception {
        Object value = field(result, path);
        return value == null ? null : value.toString();
    }
}
