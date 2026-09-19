package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Login, silent refresh, lockout and idle timeout against real PostgreSQL (API-010/014/017, NFR-SEC-001/002/004). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AuthFlowIT.Clocks.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthFlowIT {

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
            return Files.createTempDirectory("qms-keys");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleAssignmentRepository roles;
    @Autowired PasswordService passwords;
    @Autowired JwtDecoder decoder;
    @Autowired MutableClock clock;

    record TestUser(UUID id, String username) {}

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    private TestUser createUser(RoleAssignment... assignments) {
        String username = "user-" + UUID.randomUUID();
        UUID id = users.insert(username, passwords.hash(PASSWORD), "Test User", "en", clock.instant());
        roles.replaceAll(id, List.of(assignments));
        return new TestUser(id, username);
    }

    private static RoleAssignment role(Role role) {
        return new RoleAssignment(role, Set.of(), Set.of());
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private MvcResult loginOk(TestUser user) throws Exception {
        MvcResult result = login(user.username(), PASSWORD);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return result;
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        var request = post("/api/v1/auth/refresh");
        if (refreshToken != null) request.cookie(new Cookie("qms_refresh", refreshToken));
        return mvc.perform(request).andReturn();
    }

    private static String refreshCookie(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("qms_refresh=")) {
                return header.substring("qms_refresh=".length(), header.indexOf(';'));
            }
        }
        return null;
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
    }

    private static String errorCode(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.code");
    }

    private int auditCount(String action, UUID entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
    }

    // ---- login and cookies -------------------------------------------------------------------------------------

    @Test
    void loginReturnsAccessTokenInTheBodyAndRefreshTokenOnlyInAnHttpOnlyStrictCookie() throws Exception {
        TestUser user = createUser(role(Role.ORG_ADMIN));

        MvcResult result = loginOk(user);

        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.token_type")).isEqualTo("Bearer");
        assertThat((Integer) JsonPath.read(body, "$.expires_in")).isLessThanOrEqualTo(900);
        assertThat(body).doesNotContain("refresh");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).as("stateless: no HTTP session cookie (API-010)")
                .noneMatch(h -> h.toUpperCase().startsWith("JSESSIONID"));
        assertThat(result.getRequest().getSession(false)).as("no HttpSession was created").isNull();
        String cookieHeader = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("qms_refresh=")).findFirst().orElseThrow();
        assertThat(cookieHeader).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        assertThat(decoder.decode(accessToken(result)).getSubject()).isEqualTo(user.id().toString());
    }

    @Test
    void refreshTokensArePersistedOnlyAsHashes() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String raw = refreshCookie(loginOk(user));

        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, user.id());

        assertThat(stored).isNotEqualTo(raw).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, raw)).isZero();
    }

    @Test
    void anAccessTokenAuthenticatesAndMeShowsRolesAndScope() throws Exception {
        UUID site = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        TestUser user = createUser(new RoleAssignment(Role.TEAM_ADMIN, Set.of(site), Set.of(group)));
        String token = accessToken(loginOk(user));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.username").value(user.username()))
                .andExpect(jsonPath("$.roles[0]").value("team_admin"))
                .andExpect(jsonPath("$.sites[0]").value(site.toString()))
                .andExpect(jsonPath("$.groups[0]").value(group.toString()));
    }

    @Test
    void protectedEndpointsRejectMissingMalformedAndForgedTokensWithTheEnvelope() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"))
                .andExpect(jsonPath("$.error.trace_id").isNotEmpty());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("token_invalid"));
        String algNone = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("{\"sub\":\"" + UUID.randomUUID() + "\",\"roles\":[\"system_admin\"]}").getBytes())
                + ".";
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + algNone))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("token_invalid"));
    }

    @Test
    void unknownUserAndWrongPasswordLookIdenticalAndAreAudited() throws Exception {
        TestUser user = createUser(role(Role.AGENT));

        MvcResult wrong = login(user.username(), "Wrong-Password-1");
        MvcResult unknown = login("nobody-" + UUID.randomUUID(), "Wrong-Password-1");

        assertThat(wrong.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(wrong)).isEqualTo(errorCode(unknown)).isEqualTo("invalid_credentials");
        assertThat((String) JsonPath.read(wrong.getResponse().getContentAsString(), "$.error.message"))
                .isEqualTo(JsonPath.read(unknown.getResponse().getContentAsString(), "$.error.message"));
        assertThat(auditCount("auth.login.failed", user.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'auth.login.failed' AND actor_id IS NULL AND entity_id IS NULL AND after->>'reason' = 'unknown_user'",
                        Integer.class))
                .isPositive();
    }

    @Test
    void successfulLoginIsAudited() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        loginOk(user);
        assertThat(auditCount("auth.login.succeeded", user.id())).isEqualTo(1);
    }

    @Test
    void aBlankUsernameIsAValidationErrorNotACredentialsError() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- refresh: rotation, reuse detection, idle timeout (API-014, NFR-SEC-004) --------------------------------

    @Test
    void refreshRotatesTheTokenAndIssuesAWorkingAccessToken() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String first = refreshCookie(loginOk(user));

        MvcResult refreshed = refresh(first);

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        String second = refreshCookie(refreshed);
        assertThat(second).isNotBlank().isNotEqualTo(first);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken(refreshed)))
                .andExpect(status().isOk());
    }

    @Test
    void reusingARefreshTokenRevokesTheWholeFamilyAndRaisesAnAuditEvent() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String first = refreshCookie(loginOk(user));
        String second = refreshCookie(refresh(first)); // legitimate rotation

        MvcResult replay = refresh(first); // an attacker (or a bug) presents the already-used token

        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(replay)).isEqualTo("token_invalid");
        assertThat(refresh(second).getResponse().getStatus()).as("the legitimate newer token is dead too").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, user.id()))
                .isZero();
        assertThat(auditCount("auth.refresh.reuse_detected", user.id())).isEqualTo(1);
    }

    @Test
    void twoSimultaneousRefreshesOfTheSameTokenLeaveNothingUsable() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String token = refreshCookie(loginOk(user));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return refresh(token);
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        String winnerCookie = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            statuses.add(result.getResponse().getStatus());
            if (result.getResponse().getStatus() == 200) winnerCookie = refreshCookie(result);
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        assertThat(refresh(winnerCookie).getResponse().getStatus()).as("reuse detection revoked the family").isEqualTo(401);
    }

    @Test
    void missingOrGarbageRefreshTokenIsTokenInvalid() throws Exception {
        assertThat(errorCode(refresh(null))).isEqualTo("token_invalid");
        assertThat(errorCode(refresh("garbage"))).isEqualTo("token_invalid");
    }

    @Test
    void adminRolesIdleOutAfterThirtyMinutesAndAgentConsolesAfterTwelveHours() throws Exception {
        TestUser admin = createUser(role(Role.ORG_ADMIN));
        TestUser agent = createUser(role(Role.AGENT));
        String adminToken = refreshCookie(loginOk(admin));
        String agentToken = refreshCookie(loginOk(agent));

        clock.advance(Duration.ofMinutes(31));

        assertThat(errorCode(refresh(adminToken))).isEqualTo("token_invalid");
        MvcResult agentRefreshed = refresh(agentToken);
        assertThat(agentRefreshed.getResponse().getStatus()).isEqualTo(200);

        clock.advance(Duration.ofHours(11)); // sliding window: still inside 12 h of the last use
        MvcResult again = refresh(refreshCookie(agentRefreshed));
        assertThat(again.getResponse().getStatus()).isEqualTo(200);

        clock.advance(Duration.ofHours(12).plusMinutes(1));
        assertThat(errorCode(refresh(refreshCookie(again)))).isEqualTo("token_invalid");
    }

    @Test
    void aUserWithSeveralRolesGetsTheShortestIdleTimeout() throws Exception {
        TestUser both = createUser(role(Role.AGENT), role(Role.TEAM_ADMIN));
        String token = refreshCookie(loginOk(both));

        clock.advance(Duration.ofMinutes(31));

        assertThat(errorCode(refresh(token))).isEqualTo("token_invalid");
    }

    @Test
    void roleChangesTakeEffectAtTheNextRefresh() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String token = refreshCookie(loginOk(user));

        roles.replaceAll(user.id(), List.of(role(Role.ORG_ADMIN)));
        MvcResult refreshed = refresh(token);

        assertThat(decoder.decode(accessToken(refreshed)).getClaimAsStringList("roles")).containsExactly("org_admin");
    }

    @Test
    void aDisabledUserCanNeitherRefreshNorSignIn() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String token = refreshCookie(loginOk(user));

        users.setActive(user.id(), false);

        assertThat(errorCode(refresh(token))).isEqualTo("token_invalid");
        assertThat(errorCode(login(user.username(), PASSWORD))).isEqualTo("invalid_credentials");
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        String token = refreshCookie(loginOk(user));

        MvcResult out = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("qms_refresh", token))).andReturn();

        assertThat(out.getResponse().getStatus()).isEqualTo(204);
        assertThat(out.getResponse().getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith("qms_refresh=;") && h.contains("Max-Age=0"))).isTrue();
        assertThat(errorCode(refresh(token))).isEqualTo("token_invalid");
        assertThat(auditCount("auth.logout", user.id())).isEqualTo(1);
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent()); // idempotent, no cookie needed
    }

    // ---- lockout (NFR-SEC-002) ---------------------------------------------------------------------------------

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheCorrectPasswordAndItIsLoggedAndAudited(CapturedOutput output) throws Exception {
        TestUser user = createUser(role(Role.AGENT));

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(errorCode(login(user.username(), "Wrong-Password-" + attempt))).isEqualTo("invalid_credentials");
        }
        MvcResult fifth = login(user.username(), "Wrong-Password-5");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(423);
        assertThat(errorCode(fifth)).isEqualTo("account_locked");
        assertThat((Integer) JsonPath.read(fifth.getResponse().getContentAsString(), "$.error.details.retry_after_seconds")).isBetween(1, 900);

        MvcResult correct = login(user.username(), PASSWORD);
        assertThat(correct.getResponse().getStatus()).isEqualTo(423);

        assertThat(auditCount("auth.lockout", user.id())).isEqualTo(1);
        assertThat(output.getAll()).contains("Account locked").contains(user.id().toString());
    }

    @Test
    void theLockExpiresAfterFifteenMinutesAndACorrectPasswordThenWorks() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        for (int attempt = 1; attempt <= 5; attempt++) login(user.username(), "Wrong-Password-" + attempt);
        assertThat(errorCode(login(user.username(), PASSWORD))).isEqualTo("account_locked");

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(login(user.username(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCount() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        for (int attempt = 1; attempt <= 4; attempt++) login(user.username(), "Wrong-Password-" + attempt);
        loginOk(user);

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(errorCode(login(user.username(), "Wrong-Password-" + attempt))).isEqualTo("invalid_credentials");
        }
        assertThat(login(user.username(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- change password ---------------------------------------------------------------------------------------

    @Test
    void changingPasswordEnforcesPolicyAndHistoryThenEndsAllSessions() throws Exception {
        TestUser user = createUser(role(Role.AGENT));
        MvcResult session = loginOk(user);
        String access = accessToken(session);
        String refreshToken = refreshCookie(session);

        MvcResult tooShort = mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"short\"}"))
                .andReturn();
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(tooShort)).isEqualTo("validation_failed");
        assertThat(tooShort.getResponse().getContentAsString()).contains("too_short");

        MvcResult reused = mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(reused.getResponse().getContentAsString()).contains("reused");

        MvcResult wrongCurrent = mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"Not-My-Password-1\",\"new_password\":\"Brand-New-Pass-7\"}"))
                .andReturn();
        assertThat(errorCode(wrongCurrent)).isEqualTo("invalid_credentials");

        mvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"Brand-New-Pass-7\"}"))
                .andExpect(status().isNoContent());

        assertThat(errorCode(refresh(refreshToken))).as("existing sessions end").isEqualTo("token_invalid");
        assertThat(login(user.username(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(user.username(), "Brand-New-Pass-7").getResponse().getStatus()).isEqualTo(200);
        assertThat(auditCount("auth.password.changed", user.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM audit_log a WHERE action = 'auth.password.changed' AND entity_id = ?", String.class, user.id()))
                .doesNotContain("Brand-New-Pass-7").doesNotContain(PASSWORD);
    }

    // ---- logging (API-018) -------------------------------------------------------------------------------------

    @Test
    void authorizationDenialsAreLoggedWithoutTheTokenAndUseTheForbiddenEnvelope(CapturedOutput output) throws Exception {
        TestUser agent = createUser(role(Role.AGENT));
        String token = accessToken(loginOk(agent));

        mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));

        assertThat(output.getAll()).contains("Authorization denied").contains(agent.id().toString()).contains("/api/v1/audit");
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void tokensAndTicketSecretsAreRedactedEvenIfSomeoneLogsThem(CapturedOutput output) throws Exception {
        String token = accessToken(loginOk(createUser(role(Role.AGENT))));

        LoggerFactory.getLogger("redaction.probe").warn("upstream said Authorization: Bearer {} and X-Ticket-Secret: s3cr3t-value", token);

        assertThat(output.getAll()).contains("redaction.probe").doesNotContain(token).doesNotContain("s3cr3t-value");
    }
}
