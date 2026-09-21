package com.qms.platform.connectivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.mobile.CapturingVisitorOtpMailer;
import com.qms.mobile.VisitorAuthTestSupport;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Internet-loss degradation end to end against real PostgreSQL (ticket 44, SRS §6.3, §27.2 U7, FR-QUE-202,
 * FR-MOB-041, ADR-0001): once {@link InternetConnectivityMonitor} detects the Site's own uplink is down, remote join
 * is shown as unavailable (GET) and refused (POST), and Web Push is shown as unavailable — while an already-remote
 * ticket's own in-person check-in and ordinary walk-in issuance on the LAN keep working exactly as before, and each
 * transition writes one audit entry. {@link Fakes.FakeReachabilityChecker} stands in for the real internet so the
 * suite never dials out, the same "fake the seam, drive it directly" shape {@code MutableClock} already gives tests
 * that need a specific instant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, InternetLossDegradationIT.Fakes.class, VisitorAuthTestSupport.Fakes.class})
class InternetLossDegradationIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newTempDir("qms-keys-connectivity");
    static final Path VAPID_KEY_DIR = newTempDir("qms-vapid-connectivity");

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeReachabilityChecker fakeReachabilityChecker() {
            return new FakeReachabilityChecker();
        }

        /** Starts reachable, exactly like {@link InternetConnectivityMonitor} itself; a test flips it directly. */
        static class FakeReachabilityChecker implements InternetReachabilityChecker {
            private volatile boolean up = true;

            void up(boolean up) {
                this.up = up;
            }

            @Override
            public boolean reachable(URI target) {
                return up;
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.notification.web-push.key-dir", VAPID_KEY_DIR::toString);
        // A non-empty probe target is required for InternetConnectivityMonitor#check to do anything at all (empty
        // is the fail-open default); the fake checker above never actually dials it. Threshold 1 so a single failing
        // sweep flips state at once, and the cron stays off (the class-wide default) so only this test's own
        // monitor.check() calls ever move it.
        registry.add("qms.connectivity.probe-targets", () -> "https://internet.example.invalid/");
        registry.add("qms.connectivity.failure-threshold", () -> "1");
    }

    private static Path newTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InternetConnectivityMonitor monitor;
    @Autowired Fakes.FakeReachabilityChecker checker;
    @Autowired CapturingVisitorOtpMailer mailer;

    @BeforeEach
    void startReachable() {
        // The monitor is a Spring singleton shared by every test method in this class (and the audit table is real
        // Postgres, not rolled back between them), so each test starts from a known state rather than trusting
        // whatever the previous one left behind. This may itself write a "restored" audit entry; every assertion
        // below counts the *change* in a row count from a point after this reset, never an absolute count.
        checker.up(true);
        monitor.check();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Integer countAudit(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
    }

    // ---- fixtures, the same shape RemoteJoinIT already uses -----------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private Setup setup(String prefix) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main', ?, 'Asia/Dhaka', '1 Road', 'en', '[\"en\"]'::jsonb)",
                site, "S-" + prefix);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Desk\"}'::jsonb, ?)", group, site, "G" + prefix);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"mobile\"]'::jsonb, true)",
                service, group, "T" + prefix);
        return new Setup(site, group, service);
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private String staffToken(Role role, UUID... sites) throws Exception {
        UUID user = createUser(role.wire());
        jdbc.update(
                connection -> {
                    var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, user);
                    ps.setString(3, role.wire());
                    ps.setArray(4, connection.createArrayOf("uuid", sites));
                    ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                    return ps;
                });
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
    }

    private String visitorToken(String email) throws Exception {
        return VisitorAuthTestSupport.mintAccessToken(mvc, mailer, email);
    }

    private void enableVirtualQueue(String admin, UUID service) throws Exception {
        MvcResult set = call(put("/api/v1/services/" + service + "/remote-rule"), admin, "{\"virtual_queue_enabled\":true}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
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
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    // ---- detection: FR-QUE-202, ADR-0001 ---------------------------------------------------------------------------

    @Test
    void aSweepWithEveryTargetUnreachableFlipsTheSiteOfflineAndWritesOneAuditEntry() throws Exception {
        assertThat(monitor.reachable()).isTrue();
        int before = countAudit("connectivity.internet_lost");

        checker.up(false);
        monitor.check();

        assertThat(monitor.reachable()).isFalse();
        assertThat(countAudit("connectivity.internet_lost")).isEqualTo(before + 1);

        // A second failing sweep does not write a second entry: only the transition is audited, not every sweep.
        monitor.check();
        assertThat(countAudit("connectivity.internet_lost")).isEqualTo(before + 1);
    }

    @Test
    void oneReachableSweepRestoresTheSiteAndWritesItsOwnAuditEntry() throws Exception {
        checker.up(false);
        monitor.check();
        assertThat(monitor.reachable()).isFalse();
        int before = countAudit("connectivity.internet_restored");

        checker.up(true);
        monitor.check();

        assertThat(monitor.reachable()).isTrue();
        assertThat(countAudit("connectivity.internet_restored")).isEqualTo(before + 1);
    }

    // ---- remote join shown as unavailable, then refused: FR-MOB-041, FR-QUE-202 -------------------------------------

    @Test
    void remoteJoinPolicyShowsInternetUnavailableAndTheJoinItselfIsRefused() throws Exception {
        Setup s = setup("RJ");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("loss-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service());

        MvcResult beforeLoss = call(get("/api/v1/remote-join/" + s.service()), visitor, null);
        assertThat((Boolean) field(beforeLoss, "$.internet_available")).isTrue();

        checker.up(false);
        monitor.check();

        MvcResult policy = call(get("/api/v1/remote-join/" + s.service()), visitor, null);
        assertThat(status(policy)).as(body(policy)).isEqualTo(200);
        assertThat((Boolean) field(policy, "$.internet_available")).isFalse();

        MvcResult join = call(post("/api/v1/remote-join/" + s.service()).header("Idempotency-Key", UUID.randomUUID().toString()), visitor, "{}");
        assertThat(status(join)).as(body(join)).isEqualTo(409);
        assertThat((String) field(join, "$.error.details.reason")).isEqualTo("internet_unreachable");
        assertThat((String) field(join, "$.error.code")).isEqualTo("conflict");

        // Restored: shown available again and the join succeeds.
        checker.up(true);
        monitor.check();
        MvcResult afterRestore = call(post("/api/v1/remote-join/" + s.service()).header("Idempotency-Key", UUID.randomUUID().toString()), visitor, "{}");
        assertThat(status(afterRestore)).as(body(afterRestore)).isEqualTo(201);
    }

    // ---- Web Push shown as unavailable: FR-QUE-202 -------------------------------------------------------------------

    @Test
    void theWebPushKeyEndpointReportsAvailabilityFollowingTheSiteSOwnConnectivity() throws Exception {
        MvcResult up = mvc.perform(get("/api/v1/notification-config/web-push-key")).andReturn();
        assertThat((Boolean) field(up, "$.available")).isTrue();

        checker.up(false);
        monitor.check();

        MvcResult down = mvc.perform(get("/api/v1/notification-config/web-push-key")).andReturn();
        assertThat(status(down)).isEqualTo(200);
        assertThat((Boolean) field(down, "$.available")).isFalse();
        assertThat((String) field(down, "$.public_key")).isNotBlank();
    }

    // ---- unaffected: an existing remote ticket's own check-in, and walk-in issuance (ADR-0001, FR-MOB-041) -----------

    @Test
    void anExistingRemoteTicketStillChecksInAndWalkInIssuanceStillWorksWhileTheSiteIsOffline() throws Exception {
        Setup s = setup("UN");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String reception = staffToken(Role.RECEPTION_OPERATOR, s.site());
        String visitor = visitorToken("unaffected-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service());

        // Joined while the Site still had internet.
        MvcResult joined = call(post("/api/v1/remote-join/" + s.service()).header("Idempotency-Key", UUID.randomUUID().toString()), visitor, "{}");
        assertThat(status(joined)).as(body(joined)).isEqualTo(201);
        String ticketId = field(joined, "$.id");
        String secret = field(joined, "$.secret");

        checker.up(false);
        monitor.check();
        assertThat(monitor.reachable()).isFalse();

        // Its own in-person check-in (site QR) still works: this never asks connectivity at all.
        MockHttpServletRequestBuilder checkIn = post("/api/v1/tickets/" + ticketId + "/check-in")
                .header("X-Ticket-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"qr\"}");
        MvcResult checkInResult = mvc.perform(checkIn).andReturn();
        assertThat(status(checkInResult)).as(body(checkInResult)).isEqualTo(200);
        assertThat((String) field(checkInResult, "$.state")).isEqualTo("waiting");

        // Walk-in issuance on the LAN keeps working: it never reaches IssuanceGate#forRemoteJoin at all.
        MvcResult walkIn = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception, "{\"service_id\":\"" + s.service() + "\"}");
        assertThat(status(walkIn)).as(body(walkIn)).isEqualTo(201);
    }
}
