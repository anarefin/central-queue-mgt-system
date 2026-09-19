package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.support.PostgresContainerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** NFR-SEC-002: the lockout threshold is configuration, not code. */
@SpringBootTest(properties = "qms.security.lockout.max-attempts=2")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class LockoutConfigIT {

    static final Path KEYS = keyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEYS::toString);
    }

    private static Path keyDir() {
        try {
            return Files.createTempDirectory("qms-keys-lockout");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordService passwords;

    private String attempt(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Nope-Nope-Nope-1\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.error.code");
    }

    @Test
    void twoFailuresLockWhenConfiguredForTwo() throws Exception {
        String username = "user-" + UUID.randomUUID();
        users.insert(username, passwords.hash("Correct-Horse-9"), null, null, Instant.now());

        assertThat(attempt(username)).isEqualTo("invalid_credentials");
        assertThat(attempt(username)).isEqualTo("account_locked");
    }
}
