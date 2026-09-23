package com.qms.configuration.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
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
 * Ticket 68's {@code multi_site} flag against real PostgreSQL, in a test class of its own: it needs a database with
 * no Site in it yet, which only holds on the very first request of a fresh context — every other feature-flag
 * scenario lives together in {@code com.qms.issuance.setup.FeatureFlagsEnforcedIT} instead, since none of the other
 * five cares how many Sites already exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class MultiSiteFeatureFlagIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-multi-site-flag");
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

    /** An organisation-wide staff user (no site restriction), the only kind allowed to add a Site at all. */
    private String orgWideToken(Role role) throws Exception {
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

    private static String siteJson(String code) {
        return "{\"name\":\"Main campus\",\"code\":\"" + code + "\",\"timezone\":\"Asia/Dhaka\",\"address\":\"1 Campus Road, Dhaka\","
                + "\"default_language\":\"bn\",\"enabled_languages\":[\"bn\",\"en\"]}";
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void theFirstSiteIsNeverBlockedButASecondIsRefusedWhileMultiSiteIsOff() throws Exception {
        String admin = orgWideToken(Role.ORG_ADMIN);

        MvcResult flagOff = call(put("/api/v1/setup/feature-flags/multi_site"), admin, "{\"enabled\":false}");
        assertThat(status(flagOff)).as(body(flagOff)).isEqualTo(200);

        // The very first Site is never blocked: an installation always needs at least one.
        MvcResult first = call(post("/api/v1/sites"), admin, siteJson(unique("S")));
        assertThat(status(first)).as(body(first)).isEqualTo(201);

        MvcResult second = call(post("/api/v1/sites"), admin, siteJson(unique("S")));
        assertThat(status(second)).as(body(second)).isEqualTo(409);
        assertThat((String) field(second, "$.error.details.reason")).isEqualTo("feature_disabled");
        assertThat((String) field(second, "$.error.details.feature")).isEqualTo("multi_site");

        MvcResult flagOn = call(put("/api/v1/setup/feature-flags/multi_site"), admin, "{\"enabled\":true}");
        assertThat(status(flagOn)).as(body(flagOn)).isEqualTo(200);

        MvcResult third = call(post("/api/v1/sites"), admin, siteJson(unique("S")));
        assertThat(status(third)).as(body(third)).isEqualTo(201);
    }
}
