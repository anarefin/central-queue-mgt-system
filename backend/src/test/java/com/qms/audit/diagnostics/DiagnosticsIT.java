package com.qms.audit.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FR-OPS-040 against real PostgreSQL: the diagnostics bundle is System Administrator only, contains the three
 * expected files, and exporting it is itself an audited action (FR-SEC-040, DoD §27.5 items 4 and 5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class DiagnosticsIT {

    private static final String PASSWORD = "Correct-Horse-9";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private String token(Role role) throws Exception {
        UUID user = UUID.randomUUID();
        String username = role.wire() + "-" + user;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, 'en')",
                user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire());
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.access_token");
    }

    private MvcResult diagnostics(String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/ops/diagnostics");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    @Test
    void aNonSystemAdminIsRefused() throws Exception {
        String orgAdminToken = token(Role.ORG_ADMIN);

        MvcResult result = diagnostics(orgAdminToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        MvcResult result = diagnostics(null);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aSystemAdminDownloadsAZipWithVersionsConfigAndRecentEventsAndTheExportIsAudited() throws Exception {
        String adminToken = token(Role.SYSTEM_ADMIN);
        Integer auditCountBefore = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ops.diagnostics_exported'", Integer.class);

        MvcResult result = diagnostics(adminToken);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("qms-diagnostics-").contains(".zip");

        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                entries.add(entry.getName());
                if (entry.getName().equals("config.txt")) {
                    String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    // Never any secret-looking property, only the fixed allow-list (NFR-SEC-013).
                    assertThat(content).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret");
                }
                entry = zip.getNextEntry();
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertThat(entries).containsExactlyInAnyOrder("versions.txt", "config.txt", "recent-events.txt");

        Integer auditCountAfter = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ops.diagnostics_exported'", Integer.class);
        assertThat(auditCountAfter).isEqualTo(auditCountBefore + 1);
    }
}
