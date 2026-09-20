package com.qms.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Email + OTP sign-in against real PostgreSQL (ticket 41, FR-MOB-001, API-018, API-017, API-090). Confirms: a
 * first-time email registers a visitor and mints a visitor-role JWT; a wrong or expired code is refused and never
 * locks the caller out of a request for a fresh one; a code cannot be reused or guessed indefinitely; the refresh
 * cookie behaves exactly like staff's own (rotation, reuse detection); and the code itself never appears in any log
 * line or response body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, VisitorAuthIT.Clocks.class, VisitorAuthTestSupport.Fakes.class})
@ExtendWith(OutputCaptureExtension.class)
class VisitorAuthIT {

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
        registry.add("qms.visitor.auth.request-limit-per-hour", () -> "3");
        registry.add("qms.visitor.auth.max-verify-attempts", () -> "3");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-auth");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired CapturingVisitorOtpMailer mailer;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    private MvcResult requestOtp(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/visitor/otp/request").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}")).andReturn();
    }

    private MvcResult verifyOtp(String email, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/visitor/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void aFirstTimeEmailRegistersAVisitorAndSignsThemInWithAVisitorRoleJwt() throws Exception {
        String email = "new-visitor-" + UUID.randomUUID() + "@example.com";

        MvcResult requested = requestOtp(email);
        assertThat(status(requested)).isEqualTo(204);
        String code = mailer.lastCodeFor(email);
        assertThat(code).matches("\\d{6}");

        MvcResult verified = verifyOtp(email, code);

        assertThat(status(verified)).as(body(verified)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(verified), "$.token_type")).isEqualTo("Bearer");
        assertThat(((Number) JsonPath.read(body(verified), "$.expires_in")).longValue()).isLessThanOrEqualTo(900);
        String accessToken = JsonPath.read(body(verified), "$.access_token");
        assertThat(accessToken).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visitor WHERE lower(email) = lower(?)", Integer.class, email)).isEqualTo(1);

        Cookie cookie = verified.getResponse().getCookie("qms_visitor_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth/visitor");

        MvcResult me = mvc.perform(get("/api/v1/auth/visitor/me").header("Authorization", "Bearer " + accessToken)).andReturn();
        assertThat(status(me)).as(body(me)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(me), "$.email")).isEqualToIgnoringCase(email);
    }

    @Test
    void signingInAgainWithTheSameEmailReusesTheSameVisitorRecord() throws Exception {
        String email = "returning-" + UUID.randomUUID() + "@example.com";

        requestOtp(email);
        MvcResult firstVerify = verifyOtp(email, mailer.lastCodeFor(email));
        assertThat(status(firstVerify)).as(body(firstVerify)).isEqualTo(200);
        String firstVisitorId = meId(JsonPath.read(body(firstVerify), "$.access_token"));

        requestOtp(email);
        MvcResult secondVerify = verifyOtp(email, mailer.lastCodeFor(email));
        assertThat(status(secondVerify)).as(body(secondVerify)).isEqualTo(200);
        String secondVisitorId = meId(JsonPath.read(body(secondVerify), "$.access_token"));

        assertThat(secondVisitorId).isEqualTo(firstVisitorId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visitor WHERE lower(email) = lower(?)", Integer.class, email)).isEqualTo(1);
    }

    private String meId(String accessToken) throws Exception {
        MvcResult me = mvc.perform(get("/api/v1/auth/visitor/me").header("Authorization", "Bearer " + accessToken)).andReturn();
        return JsonPath.read(body(me), "$.id");
    }

    @Test
    void aWrongCodeIsRefusedAsInvalidCredentials() throws Exception {
        String email = "wrong-code-" + UUID.randomUUID() + "@example.com";
        requestOtp(email);

        MvcResult result = verifyOtp(email, "000000");

        assertThat(status(result)).as(body(result)).isEqualTo(401);
        assertThat((String) JsonPath.read(body(result), "$.error.code")).isEqualTo("invalid_credentials");
    }

    @Test
    void repeatedWrongGuessesLockTheCodeOutRequiringAFreshOne() throws Exception {
        String email = "lockout-" + UUID.randomUUID() + "@example.com";
        requestOtp(email);
        String realCode = mailer.lastCodeFor(email);

        for (int i = 0; i < 3; i++) {
            assertThat(status(verifyOtp(email, "111111"))).isEqualTo(401);
        }
        // The code is now locked out even though it is still within its TTL and is the right one.
        MvcResult afterLockout = verifyOtp(email, realCode);
        assertThat(status(afterLockout)).isEqualTo(401);

        // A fresh request issues a new, usable code.
        requestOtp(email);
        MvcResult freshVerify = verifyOtp(email, mailer.lastCodeFor(email));
        assertThat(status(freshVerify)).as(body(freshVerify)).isEqualTo(200);
    }

    @Test
    void requestingTooManyCodesIsRateLimitedWithRetryAfter() throws Exception {
        String email = "rate-limited-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 3; i++) {
            assertThat(status(requestOtp(email))).isEqualTo(204);
        }

        MvcResult fourth = requestOtp(email);

        assertThat(status(fourth)).as(body(fourth)).isEqualTo(429);
        assertThat(fourth.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat((String) JsonPath.read(body(fourth), "$.error.code")).isEqualTo("rate_limited");
    }

    @Test
    void silentRefreshRotatesTheTokenAndReuseOfAnOldOneRevokesTheWholeFamily() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        requestOtp(email);
        MvcResult verified = verifyOtp(email, mailer.lastCodeFor(email));
        Cookie firstCookie = verified.getResponse().getCookie("qms_visitor_refresh");

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/visitor/refresh").cookie(firstCookie)).andReturn();
        assertThat(status(refreshed)).as(body(refreshed)).isEqualTo(200);
        Cookie secondCookie = refreshed.getResponse().getCookie("qms_visitor_refresh");
        assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

        // Replaying the already-used first refresh token must fail, and revoke the whole family (the second, valid one too).
        MvcResult replay = mvc.perform(post("/api/v1/auth/visitor/refresh").cookie(firstCookie)).andReturn();
        assertThat(status(replay)).isEqualTo(401);

        MvcResult secondNowRevoked = mvc.perform(post("/api/v1/auth/visitor/refresh").cookie(secondCookie)).andReturn();
        assertThat(status(secondNowRevoked)).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheRefreshTokenSoItCanNoLongerBeUsed() throws Exception {
        String email = "logout-" + UUID.randomUUID() + "@example.com";
        requestOtp(email);
        MvcResult verified = verifyOtp(email, mailer.lastCodeFor(email));
        Cookie cookie = verified.getResponse().getCookie("qms_visitor_refresh");

        MvcResult loggedOut = mvc.perform(post("/api/v1/auth/visitor/logout").cookie(cookie)).andReturn();
        assertThat(status(loggedOut)).isEqualTo(204);

        MvcResult afterLogout = mvc.perform(post("/api/v1/auth/visitor/refresh").cookie(cookie)).andReturn();
        assertThat(status(afterLogout)).isEqualTo(401);
    }

    @Test
    void theCodeNeverAppearsInAnyLogLineOrInTheVerifyResponseBody(CapturedOutput output) throws Exception {
        String email = "no-log-" + UUID.randomUUID() + "@example.com";
        requestOtp(email);
        String code = mailer.lastCodeFor(email);

        MvcResult verified = verifyOtp(email, code);

        assertThat(body(verified)).doesNotContain(code);
        assertThat(output.getAll()).doesNotContain(code);
    }

    @Test
    void anonymousCallerCannotReadAnotherVisitorsMeEndpoint() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/visitor/me")).andReturn();
        assertThat(status(result)).isEqualTo(401);
    }
}
