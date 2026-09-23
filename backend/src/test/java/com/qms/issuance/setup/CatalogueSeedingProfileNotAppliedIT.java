package com.qms.issuance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Ticket 67: {@code POST /setup/seed-catalogue} refuses {@code conflict}/{@code profile_not_applied} on a truly
 * fresh installation. Kept in its own Spring context (own Testcontainers database) rather than folded into {@link
 * CatalogueSeedingIT}, because "no vertical profile has ever been applied" is an organisation-wide, one-way fact
 * (there is no "unapply") that every other test in that class deliberately sets by calling reset -- sharing a
 * database with them would make this test's outcome depend on JUnit's (unspecified) method execution order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, CatalogueSeedingProfileNotAppliedIT.Clocks.class})
class CatalogueSeedingProfileNotAppliedIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

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
            return Files.createTempDirectory("qms-keys-catalogue-seeding-fresh");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesWithProfileNotAppliedOnAFreshInstallation() throws Exception {
        clock.set(Instant.parse("2026-09-22T09:00:00Z"));

        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Fresh site', ?, 'Asia/Dhaka', '1 Main Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));

        UUID adminId = UUID.randomUUID();
        String username = "fresh-admin-" + adminId;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, 'en')",
                adminId, username, new BCryptPasswordEncoder(12).encode(PASSWORD), username);
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, adminId);
            ps.setString(3, "system_admin");
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });

        Instant businessTime = clock.instant();
        clock.set(Instant.now());
        MvcResult login;
        try {
            login = mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
        } finally {
            clock.set(businessTime);
        }
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        String token = JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.access_token");

        MvcResult seeded = mvc.perform(post("/api/v1/setup/seed-catalogue")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site_id\":\"" + site + "\"}"))
                .andReturn();

        String body = seeded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(seeded.getResponse().getStatus()).as(body).isEqualTo(409);
        assertThat((String) JsonPath.read(body, "$.error.details.reason")).isEqualTo("profile_not_applied");
    }
}
