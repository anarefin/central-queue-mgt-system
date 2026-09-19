package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** FR-INT-001: a different provider (a stand-in for OIDC or LDAP) slots in and the rest of sign-in is unchanged. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, IdentityProviderIT.SingleSignOn.class})
class IdentityProviderIT {

    static final Map<String, UUID> DIRECTORY = new ConcurrentHashMap<>();

    @TestConfiguration
    static class SingleSignOn {
        @Bean
        @Primary
        IdentityProvider directoryProvider() {
            return (username, password) -> {
                UUID id = DIRECTORY.get(username);
                if (id == null || !"sso-secret".equals(password)) throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
                return new IdentityProvider.Authenticated(id, false);
            };
        }
    }

    static final Path KEYS = keyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEYS::toString);
    }

    private static Path keyDir() {
        try {
            return Files.createTempDirectory("qms-keys-idp");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleAssignmentRepository roles;
    @Autowired PasswordService passwords;

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    @Test
    void aReplacementProviderAuthenticatesAndTheApplicationStillIssuesItsOwnTokensWithLocalRoles() throws Exception {
        UUID id = users.insert("alice-" + UUID.randomUUID(), passwords.hash("Unused-Local-Pass-1"), null, null, Instant.now());
        roles.replaceAll(id, List.of(new RoleAssignment(Role.TEAM_ADMIN, Set.of(), Set.of())));
        DIRECTORY.put("sso-alice", id);

        MvcResult ok = login("sso-alice", "sso-secret");

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith("qms_refresh="))).isTrue();
        assertThat(ok.getResponse().getContentAsString()).contains("access_token");
        assertThat(login("sso-alice", "wrong").getResponse().getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(login("nobody", "sso-secret").getResponse().getContentAsString(), "$.error.code"))
                .isEqualTo("invalid_credentials");
    }
}
