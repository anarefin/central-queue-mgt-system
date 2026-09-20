package com.qms.configuration.catalogue;

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
 * Journey templates over HTTP (ticket 31, FR-QUE-060): an Org Admin creates a template scoped to a Service group,
 * lists and deactivates/reactivates it; and, issued end to end, Reception issues a Journey from that template
 * (FR-ISS-022), which is where {@code journey_template_id} is exercised (the ad hoc path is covered by
 * {@code com.qms.session.JourneyIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class JourneyTemplateAdminIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-journey-templates");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private record World(UUID site, UUID group, UUID a, UUID b) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        UUID a = newService(group, "A", "Registration");
        UUID b = newService(group, "B", "Consultation");
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, a);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, b);
        return new World(site, group, a, b);
    }

    private UUID newService(UUID group, String prefix, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, ?::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, "{\"en\":\"" + name + "\"}", prefix);
        return id;
    }

    private record Agent(UUID id, String token) {}

    private Agent user(Role role, UUID site, UUID teamOf) throws Exception {
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
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        if (teamOf != null) {
            jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
        }
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        return new Agent(user, JsonPath.read(body(result), "$.access_token"));
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

    @Test
    void anOrgAdminCreatesListsAndDeactivatesATemplateScopedToItsGroup() throws Exception {
        World w = world();
        Agent admin = user(Role.ORG_ADMIN, w.site(), null);

        MvcResult created = call(
                post("/api/v1/service-groups/" + w.group() + "/journey-templates"),
                admin.token(),
                "{\"name_i18n\":{\"en\":\"New patient\"},\"ordered\":true,\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"]}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((Boolean) field(created, "$.active")).isTrue();
        List<String> stopServiceIds = field(created, "$.stops[*].service_id");
        assertThat(stopServiceIds).containsExactly(w.a().toString(), w.b().toString());
        String templateId = field(created, "$.id");

        MvcResult listed = call(get("/api/v1/service-groups/" + w.group() + "/journey-templates"), admin.token(), null);
        assertThat(status(listed)).as(body(listed)).isEqualTo(200);
        assertThat((List<String>) field(listed, "$.items[*].id")).contains(templateId);

        MvcResult deactivated = call(post("/api/v1/journey-templates/" + templateId + "/deactivate"), admin.token(), null);
        assertThat(status(deactivated)).as(body(deactivated)).isEqualTo(200);
        assertThat((Boolean) field(deactivated, "$.active")).isFalse();

        MvcResult reactivated = call(post("/api/v1/journey-templates/" + templateId + "/activate"), admin.token(), null);
        assertThat(status(reactivated)).as(body(reactivated)).isEqualTo(200);
        assertThat((Boolean) field(reactivated, "$.active")).isTrue();
    }

    @Test
    void aTemplateNeedsAtLeastTwoStopsFromItsOwnGroup() throws Exception {
        World w = world();
        Agent admin = user(Role.ORG_ADMIN, w.site(), null);

        MvcResult tooFew = call(
                post("/api/v1/service-groups/" + w.group() + "/journey-templates"), admin.token(), "{\"name_i18n\":{\"en\":\"X\"},\"ordered\":false,\"service_ids\":[\"" + w.a() + "\"]}");
        assertThat(status(tooFew)).as(body(tooFew)).isEqualTo(400);

        World other = world();
        MvcResult foreign = call(
                post("/api/v1/service-groups/" + w.group() + "/journey-templates"),
                admin.token(),
                "{\"name_i18n\":{\"en\":\"X\"},\"ordered\":false,\"service_ids\":[\"" + w.a() + "\",\"" + other.a() + "\"]}");
        assertThat(status(foreign)).as(body(foreign)).isEqualTo(400);
    }

    @Test
    void receptionIssuesAJourneyFromAnActiveTemplateOfTheSite() throws Exception {
        World w = world();
        Agent admin = user(Role.ORG_ADMIN, w.site(), null);
        jdbc.update("UPDATE journey_settings SET enabled = true WHERE id = 1");
        MvcResult created = call(
                post("/api/v1/service-groups/" + w.group() + "/journey-templates"),
                admin.token(),
                "{\"name_i18n\":{\"en\":\"New patient\"},\"ordered\":false,\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"]}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        String templateId = field(created, "$.id");
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        MvcResult templatesForSite = call(get("/api/v1/sites/" + w.site() + "/journey-templates"), reception.token(), null);
        assertThat(status(templatesForSite)).as(body(templatesForSite)).isEqualTo(200);
        assertThat((List<String>) field(templatesForSite, "$[*].id")).contains(templateId);

        MvcResult issued = call(
                post("/api/v1/journeys").header("Idempotency-Key", "tmpl-" + UUID.randomUUID()), reception.token(), "{\"journey_template_id\":\"" + templateId + "\"}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        List<String> states = field(issued, "$.stops[*].state");
        assertThat(states).containsExactly("waiting", "waiting");
    }
}
