package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** NFR-SEC-003 seam: a later MFA provider plugs in as a {@link LoginStepUp} bean and no client flow changes. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, StepUpIT.RequireTotpForAdmins.class})
class StepUpIT {

    @TestConfiguration
    static class RequireTotpForAdmins {
        @Bean
        LoginStepUp totpForOrgAdmin() {
            return (userId, roles) -> roles.contains(Role.ORG_ADMIN) ? LoginStepUp.Result.required("totp") : LoginStepUp.Result.ok();
        }
    }

    static final Path KEYS = keyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEYS::toString);
    }

    private static Path keyDir() {
        try {
            return Files.createTempDirectory("qms-keys-stepup");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleAssignmentRepository roles;
    @Autowired PasswordService passwords;

    private UUID user(String username, Role role) {
        UUID id = users.insert(username, passwords.hash("Correct-Horse-9"), null, null, Instant.now());
        roles.replaceAll(id, List.of(new RoleAssignment(role, Set.of(), Set.of())));
        return id;
    }

    private MvcResult login(String username) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Correct-Horse-9\"}"))
                .andReturn();
    }

    @Test
    void anUnsatisfiedStepUpStopsLoginWithoutIssuingAnyToken() throws Exception {
        String username = "admin-" + UUID.randomUUID();
        UUID id = user(username, Role.ORG_ADMIN);

        MvcResult result = login(username);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.error.code")).isEqualTo("unauthenticated");
        assertThat((String) JsonPath.read(body, "$.error.details.step_up")).isEqualTo("totp");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void rolesTheHookDoesNotCoverSignInAsBefore() throws Exception {
        String username = "agent-" + UUID.randomUUID();
        user(username, Role.AGENT);

        assertThat(login(username).getResponse().getStatus()).isEqualTo(200);
    }
}
