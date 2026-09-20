package com.qms.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * A visitor's own remote join against real PostgreSQL (ticket 42, SRS §13.2, FR-MOB-010..012, FR-MOB-023): the
 * virtual-queue flag gates it, the per-Service policy (distance, remote share, join window) is enforced and shown
 * before joining, the joined Ticket differs from a walk-in only by {@code origin_channel}, and every one of these is
 * a staff-administered setting reachable only by the caller's own permission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, RemoteJoinIT.Clocks.class, VisitorAuthTestSupport.Fakes.class})
class RemoteJoinIT {

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
            return Files.createTempDirectory("qms-keys-remote-join");
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

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

    private String visitorToken(String email) throws Exception {
        return VisitorAuthTestSupport.mintAccessToken(mvc, mailer, email);
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    /** Minted at real time whatever the test clock says: tokens are validated against the system clock, not this bean. */
    private String staffToken(Role role, UUID... sites) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
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
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private void enableVirtualQueue(String admin, UUID service, String extraFields) throws Exception {
        MvcResult set = call(put("/api/v1/services/" + service + "/remote-rule"), admin,
                "{\"virtual_queue_enabled\":true" + extraFields + "}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
    }

    private MvcResult joinRemote(String visitor, UUID service) throws Exception {
        return joinRemote(visitor, service, null);
    }

    private MvcResult joinRemote(String visitor, UUID service, String locationBody) throws Exception {
        return call(post("/api/v1/remote-join/" + service).header("Idempotency-Key", UUID.randomUUID().toString()), visitor, locationBody == null ? "{}" : locationBody);
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

    // ---- FR-MOB-010: only where the virtual-queue flag is on -----------------------------------------------------

    @Test
    void remoteJoinIsRefusedWhenTheVirtualQueueFlagIsOff() throws Exception {
        Setup s = setup("OF");
        String visitor = visitorToken("off-" + UUID.randomUUID() + "@example.com");

        MvcResult result = joinRemote(visitor, s.service());

        assertThat(status(result)).as(body(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("virtual_queue_disabled");
        assertThat((String) field(result, "$.error.code")).isEqualTo("conflict");
    }

    @Test
    void onceTheFlagIsOnAVisitorCanJoinAndTheTicketStartsRemote() throws Exception {
        Setup s = setup("ON");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("on-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service(), "");

        MvcResult result = joinRemote(visitor, s.service());

        assertThat(status(result)).as(body(result)).isEqualTo(201);
        assertThat((String) field(result, "$.state")).isEqualTo("remote");
        // Mobile-origin Tickets differ from any other channel only by origin_channel (§8.5): everything else about
        // the response (position, secret, numbering) is the same shape any other issuance already returns.
        assertThat((String) field(result, "$.origin_channel")).isEqualTo("mobile");
        assertThat((String) field(result, "$.secret")).isNotBlank();
        assertThat((Integer) field(result, "$.position")).isEqualTo(1);
    }

    // ---- FR-MOB-011: the per-Service policy -----------------------------------------------------------------------

    @Test
    void thePolicyIsShownBeforeJoiningIncludingTheArrivalDeadline() throws Exception {
        // FR-MOB-023: the forfeit policy and its consequences (here, the arrival deadline that governs them) must be
        // visible before the visitor commits, not only after.
        Setup s = setup("PV");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("policy-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service(), ",\"max_distance_m\":5000,\"max_remote_share_pct\":25,\"join_window_minutes\":20,\"arrival_deadline_minutes\":10");

        MvcResult result = call(get("/api/v1/remote-join/" + s.service()), visitor, null);

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((Boolean) field(result, "$.virtual_queue_enabled")).isTrue();
        assertThat((Integer) field(result, "$.max_distance_m")).isEqualTo(5000);
        assertThat((Integer) field(result, "$.max_remote_share_pct")).isEqualTo(25);
        assertThat((Integer) field(result, "$.join_window_minutes")).isEqualTo(20);
        assertThat((Integer) field(result, "$.arrival_deadline_minutes")).isEqualTo(10);
    }

    @Test
    void aVisitorBeyondTheConfiguredDistanceIsRefused() throws Exception {
        Setup s = setup("DI");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("far-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service(), ",\"max_distance_m\":1000");
        MvcResult located = call(put("/api/v1/sites/" + s.site() + "/location"), admin, "{\"latitude\":23.8103,\"longitude\":90.4125}");
        assertThat(status(located)).as(body(located)).isEqualTo(200);

        // Gazipur, about 21 km from the Site above: well past the 1 km cap.
        MvcResult tooFar = joinRemote(visitor, s.service(), "{\"latitude\":23.9999,\"longitude\":90.4203}");
        assertThat(status(tooFar)).as(body(tooFar)).isEqualTo(409);
        assertThat((String) field(tooFar, "$.error.details.reason")).isEqualTo("too_far");

        // A point a few dozen metres away is within the cap.
        MvcResult close = joinRemote(visitor, s.service(), "{\"latitude\":23.8104,\"longitude\":90.4126}");
        assertThat(status(close)).as(body(close)).isEqualTo(201);
    }

    @Test
    void aDistanceCapWithNoSiteLocationSetRefusesRatherThanGuessing() throws Exception {
        Setup s = setup("NL");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("nolocation-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service(), ",\"max_distance_m\":1000");

        MvcResult result = joinRemote(visitor, s.service(), "{\"latitude\":23.8103,\"longitude\":90.4125}");

        assertThat(status(result)).as(body(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("site_location_unset");
    }

    @Test
    void aDistanceCapWithNoPositionSuppliedIsAValidationFailure() throws Exception {
        Setup s = setup("NP");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("noposition-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service(), ",\"max_distance_m\":1000");
        call(put("/api/v1/sites/" + s.site() + "/location"), admin, "{\"latitude\":23.8103,\"longitude\":90.4125}");

        MvcResult result = joinRemote(visitor, s.service());

        assertThat(status(result)).as(body(result)).isEqualTo(400);
        assertThat((String) field(result, "$.error.code")).isEqualTo("validation_failed");
    }

    @Test
    void theRemoteShareCapRefusesOnceItWouldBeExceeded() throws Exception {
        Setup s = setup("SH");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        // At most 50% of the queue may be remote: the first remote join is allowed (0 of 0 -> 1 of 1, within cap);
        // the second is refused until a walk-in ticket widens the queue enough to make room for it.
        enableVirtualQueue(admin, s.service(), ",\"max_remote_share_pct\":50");

        MvcResult first = joinRemote(visitorToken("share1-" + UUID.randomUUID() + "@example.com"), s.service());
        assertThat(status(first)).as(body(first)).isEqualTo(201);

        MvcResult second = joinRemote(visitorToken("share2-" + UUID.randomUUID() + "@example.com"), s.service());
        assertThat(status(second)).as(body(second)).isEqualTo(409);
        assertThat((String) field(second, "$.error.details.reason")).isEqualTo("remote_share_full");

        // A walk-in ticket widens the queue; now two of three (66%) would still be over 50%, so it is still refused...
        String reception = staffToken(Role.RECEPTION_OPERATOR, s.site());
        call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception, "{\"service_id\":\"" + s.service() + "\"}");
        MvcResult stillFull = joinRemote(visitorToken("share3-" + UUID.randomUUID() + "@example.com"), s.service());
        assertThat(status(stillFull)).as(body(stillFull)).isEqualTo(409);

        // ...but a second walk-in ticket makes two of four (50%) fit exactly.
        call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception, "{\"service_id\":\"" + s.service() + "\"}");
        MvcResult nowFits = joinRemote(visitorToken("share4-" + UUID.randomUUID() + "@example.com"), s.service());
        assertThat(status(nowFits)).as(body(nowFits)).isEqualTo(201);
    }

    @Test
    void theJoinWindowStopsJoiningTooLongBeforeOpening() throws Exception {
        Setup s = setup("JW");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        // Both visitor tokens are minted here, before the clock moves into the (story) past below: a token's own
        // iat/exp are stamped from the same injected clock the OTP flow reads (unlike `staffToken`, which resets it
        // around the mint for exactly this reason), so minting after the jump would make it look already expired.
        String earlyVisitor = visitorToken("early-" + UUID.randomUUID() + "@example.com");
        String windowVisitor = visitorToken("window-" + UUID.randomUUID() + "@example.com");
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":7,\"open\":\"09:00\",\"close\":\"17:00\"}]}"); // Sunday
        enableVirtualQueue(admin, s.service(), ",\"join_window_minutes\":30");

        clock.set(Instant.parse("2026-09-19T18:00:00Z")); // 2026-09-20 00:00 Dhaka: Sunday, 9 hours before opening.
        MvcResult tooEarly = joinRemote(earlyVisitor, s.service());
        assertThat(status(tooEarly)).as(body(tooEarly)).isEqualTo(409);
        assertThat((String) field(tooEarly, "$.error.details.reason")).isEqualTo("too_early");

        clock.set(Instant.parse("2026-09-20T02:35:00Z")); // 08:35 Dhaka: 25 minutes before opening, inside the window.
        MvcResult withinWindow = joinRemote(windowVisitor, s.service());
        assertThat(status(withinWindow)).as(body(withinWindow)).isEqualTo(201);
    }

    // ---- definition of done: permission-checked server-side --------------------------------------------------

    @Test
    void anAnonymousCallerCannotJoinOrReadThePolicy() throws Exception {
        Setup s = setup("AN");

        assertThat(status(call(get("/api/v1/remote-join/" + s.service()), null, null))).isEqualTo(401);
        assertThat(status(joinRemote(null, s.service()))).isEqualTo(401);
    }

    @Test
    void aStaffTokenCannotJoinOrReadThePolicyEither() throws Exception {
        Setup s = setup("ST");
        String reception = staffToken(Role.RECEPTION_OPERATOR, s.site());

        assertThat(status(call(get("/api/v1/remote-join/" + s.service()), reception, null))).isEqualTo(403);
        assertThat(status(joinRemote(reception, s.service()))).isEqualTo(403);
    }

    @Test
    void onlyStaffWithTheServiceCataloguePermissionCanChangeTheRemoteRule() throws Exception {
        Setup s = setup("PM");
        String reception = staffToken(Role.RECEPTION_OPERATOR, s.site());

        MvcResult result = call(put("/api/v1/services/" + s.service() + "/remote-rule"), reception, "{\"virtual_queue_enabled\":true}");

        assertThat(status(result)).isEqualTo(403);
    }

    // ---- replay safety -------------------------------------------------------------------------------------------

    @Test
    void replayingTheIdempotencyKeyReturnsTheSameTicketRatherThanJoiningTwice() throws Exception {
        Setup s = setup("ID");
        String admin = staffToken(Role.ORG_ADMIN, s.site());
        String visitor = visitorToken("replay-" + UUID.randomUUID() + "@example.com");
        enableVirtualQueue(admin, s.service(), "");
        String key = UUID.randomUUID().toString();

        MvcResult first = call(post("/api/v1/remote-join/" + s.service()).header("Idempotency-Key", key), visitor, "{}");
        MvcResult replay = call(post("/api/v1/remote-join/" + s.service()).header("Idempotency-Key", key), visitor, "{}");

        assertThat(status(first)).as(body(first)).isEqualTo(201);
        assertThat(status(replay)).as(body(replay)).isEqualTo(201);
        assertThat((String) field(replay, "$.id")).isEqualTo(field(first, "$.id"));
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ?", Integer.class, s.service());
        assertThat(count).isEqualTo(1);
    }
}
