package com.qms.device;

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
 * FR-SEC-021 (ticket 54) against real PostgreSQL: a clinical-sensitivity Site's public display never names a real
 * Service — {@link DisplayStateReads} replaces it with the neutral label {@code com.qms.notification.NotificationDispatchIT}
 * proves the same flag also gives notifications.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class ClinicalSensitivityDisplayIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-clinical-display");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

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

    private record Setup(UUID site, UUID zone, UUID counter) {}

    private Setup setup(boolean clinical) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages, clinical_sensitivity)"
                        + " VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb, ?)",
                site, "CD-" + site.toString().substring(0, 8), clinical);
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Ground', 'Ground')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\"]'::jsonb, true)",
                service, group);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, 'Desk 1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
        return new Setup(site, zone, counter);
    }

    private String pairDisplay(String token, UUID site, UUID zone) throws Exception {
        MvcResult code = call(post("/api/v1/devices/pairing-codes"), token,
                "{\"kind\":\"display\",\"site_id\":\"" + site + "\",\"zone_id\":\"" + zone + "\",\"label\":\"Lobby\"}");
        assertThat(status(code)).as(body(code)).isEqualTo(201);
        MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + field(code, "$.code") + "\"}");
        assertThat(status(paired)).as(body(paired)).isEqualTo(201);
        return field(paired, "$.access_token");
    }

    @Test
    void aClinicalSensitivitySiteShowsANeutralLabelInsteadOfTheRealServiceName() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        Setup s = setup(true);
        String displayToken = pairDisplay(admin, s.site(), s.zone());

        MvcResult state = call(get("/api/v1/devices/" + deviceIdOf(displayToken) + "/display-state"), displayToken, null);
        assertThat(status(state)).as(body(state)).isEqualTo(200);
        assertThat((String) field(state, "$.next[0].service_names.en")).isEqualTo("Service");
        assertThat(body(state)).doesNotContain("Consultation");
    }

    @Test
    void aNonClinicalSiteStillShowsTheRealServiceName() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        Setup s = setup(false);
        String displayToken = pairDisplay(admin, s.site(), s.zone());

        MvcResult state = call(get("/api/v1/devices/" + deviceIdOf(displayToken) + "/display-state"), displayToken, null);
        assertThat(status(state)).as(body(state)).isEqualTo(200);
        assertThat((String) field(state, "$.next[0].service_names.en")).isEqualTo("Consultation");
    }

    /** The display's own device id, the same {@code sub} claim {@code /devices/{id}/display-state} checks against. */
    private UUID deviceIdOf(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return UUID.fromString((String) JsonPath.read(payload, "$.sub"));
    }
}
