package com.qms.integration.serviceaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Ticket 58 against real PostgreSQL, end to end: an org admin provisions a service account scoped to a site
 * (FR-CFG-106), the client id and secret it is shown exactly once are exchanged for a scoped JWT carrying
 * {@code host_system} (SRS §20.2), and that token then creates a Ticket, queries its queue status and cancels it
 * (FR-INT-030) through the very same public, anonymous ticket-secret endpoints a browser-based visitor already
 * uses — and books, then cancels, an appointment through the very same endpoint staff already does. Every one of
 * those calls is permission-checked exactly like any other principal's (§20): no privileged internal path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class ServiceAccountIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-service-account");
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

    // ---- fixtures (mirrors issuance.KioskTicketIT / appointment.AppointmentBookingIT) ----------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newGroup(UUID site, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\",\"bn\":\"বহির্বিভাগ\"}'::jsonb, ?)", id, site, prefix);
        return id;
    }

    private UUID newService(UUID group, String prefix, String channelsJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, ?::jsonb, 'both', true)",
                id, group, prefix, channelsJson);
        return id;
    }

    private Setup setup(String prefix, String channelsJson) {
        UUID site = newSite();
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix, channelsJson);
        return new Setup(site, group, service);
    }

    private UUID newVisitor(String name, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, phone, created_at) VALUES (?, ?, ?, 'general', ?, now())",
                id, "V-" + id.toString().substring(0, 8), name, phone);
        return id;
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
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", sites));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(body(login)).isEqualTo(200);
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
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.parse(body(result)).read(path);
    }

    private static String errorCode(MvcResult result) throws Exception {
        return field(result, "$.error.code");
    }

    /** Provisions a service account scoped to {@code sites} and returns its {@code client_id}/{@code client_secret}. */
    private record Credentials(String clientId, String clientSecret) {}

    private Credentials provisionServiceAccount(String admin, UUID... sites) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < sites.length; i++) {
            if (i > 0) ids.append(',');
            ids.append('"').append(sites[i]).append('"');
        }
        MvcResult created = call(post("/api/v1/service-accounts"), admin, "{\"label\":\"Bank app\",\"site_ids\":[" + ids + "]}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return new Credentials(field(created, "$.client_id"), field(created, "$.client_secret"));
    }

    private MvcResult exchangeToken(String clientId, String clientSecret) throws Exception {
        return call(post("/api/v1/auth/service-accounts/token"), null, "{\"client_id\":\"" + clientId + "\",\"client_secret\":\"" + clientSecret + "\"}");
    }

    /** Provisions a service account scoped to {@code sites} and returns its access token, ready to use as a bearer. */
    private String hostSystemToken(String admin, UUID... sites) throws Exception {
        Credentials credentials = provisionServiceAccount(admin, sites);
        MvcResult token = exchangeToken(credentials.clientId(), credentials.clientSecret());
        assertThat(status(token)).as(body(token)).isEqualTo(200);
        return field(token, "$.access_token");
    }

    private MvcResult issue(String token, String key, UUID serviceId) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/host/tickets");
        if (key != null) request.header("Idempotency-Key", key);
        return call(request, token, "{\"service_id\":\"" + serviceId + "\"}");
    }

    // ---- SRS §20.2: client id and secret exchanged for a scoped JWT ------------------------------------------------

    @Test
    void anOrgAdminProvisionsAServiceAccountAndItExchangesItsCredentialsForAScopedToken() throws Exception {
        Setup s = setup("SA", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);

        Credentials credentials = provisionServiceAccount(admin, s.site());
        assertThat(credentials.clientId()).startsWith("svc_");
        assertThat(credentials.clientSecret()).hasSizeGreaterThanOrEqualTo(32);

        MvcResult token = exchangeToken(credentials.clientId(), credentials.clientSecret());
        assertThat(status(token)).as(body(token)).isEqualTo(200);
        assertThat((String) field(token, "$.token_type")).isEqualTo("Bearer");
        assertThat(((Number) field(token, "$.expires_in")).longValue()).isLessThanOrEqualTo(900L);
    }

    @Test
    void theSecretIsNeverReadableAgainAndAWrongOrUnknownCredentialIsRefused() throws Exception {
        Setup s = setup("SB", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        Credentials credentials = provisionServiceAccount(admin, s.site());

        MvcResult read = call(get("/api/v1/service-accounts/" + jdbc.queryForObject(
                "SELECT id FROM service_account WHERE client_id = ?", UUID.class, credentials.clientId())), admin, null);
        assertThat(body(read)).as("the secret cannot be read back").doesNotContain("client_secret");

        assertThat(status(exchangeToken(credentials.clientId(), "not-the-secret"))).isEqualTo(401);
        assertThat(errorCode(exchangeToken(credentials.clientId(), "not-the-secret"))).isEqualTo("invalid_credentials");
        assertThat(status(exchangeToken("svc_no_such_client", credentials.clientSecret()))).isEqualTo(401);
    }

    @Test
    void deactivatingTheAccountRefusesFurtherTokenExchangeAndReactivatingRestoresIt() throws Exception {
        Setup s = setup("SC", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        Credentials credentials = provisionServiceAccount(admin, s.site());
        UUID id = jdbc.queryForObject("SELECT id FROM service_account WHERE client_id = ?", UUID.class, credentials.clientId());

        MvcResult deactivated = call(post("/api/v1/service-accounts/" + id + "/deactivate"), admin, null);
        assertThat(status(deactivated)).as(body(deactivated)).isEqualTo(200);
        assertThat(status(exchangeToken(credentials.clientId(), credentials.clientSecret()))).isEqualTo(401);

        call(post("/api/v1/service-accounts/" + id + "/activate"), admin, null);
        assertThat(status(exchangeToken(credentials.clientId(), credentials.clientSecret()))).isEqualTo(200);
    }

    @Test
    void onlyAnOrgAdminMayProvisionAServiceAccount() throws Exception {
        String agent = staffToken(Role.AGENT);

        MvcResult result = call(post("/api/v1/service-accounts"), agent, "{\"label\":\"x\",\"site_ids\":[\"" + UUID.randomUUID() + "\"]}");

        assertThat(status(result)).isEqualTo(403);
    }

    @Test
    void aServiceAccountCannotBeCreatedWithoutAtLeastOneSite() throws Exception {
        String admin = staffToken(Role.ORG_ADMIN);

        MvcResult result = call(post("/api/v1/service-accounts"), admin, "{\"label\":\"x\",\"site_ids\":[]}");

        assertThat(status(result)).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("validation_failed");
    }

    // ---- FR-INT-030: create a Ticket, query queue status and cancel, through the same public path any visitor uses --

    @Test
    void aHostSystemIssuesATicketQueriesItsStatusAndCancelsItThroughTheSamePublicSecretEndpointsAVisitorUses() throws Exception {
        Setup s = setup("HT", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        String host = hostSystemToken(admin, s.site());

        MvcResult issued = issue(host, UUID.randomUUID().toString(), s.service());
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        assertThat((String) field(issued, "$.origin_channel")).isEqualTo("mobile");
        assertThat((String) field(issued, "$.state")).isEqualTo("waiting");
        String secret = field(issued, "$.secret");
        assertThat(secret).isNotBlank();
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT actor_type FROM ticket_event WHERE ticket_id = ?", String.class, ticketId)).isEqualTo("host_system");

        MvcResult queried = mvc.perform(get("/api/v1/tickets/" + ticketId + "/visitor").header("X-Ticket-Secret", secret)).andReturn();
        assertThat(status(queried)).as(body(queried)).isEqualTo(200);
        assertThat((Integer) field(queried, "$.position")).isEqualTo(1);
        assertThat((String) field(queried, "$.state")).isEqualTo("waiting");

        MvcResult cancelled = mvc.perform(post("/api/v1/tickets/" + ticketId + "/visitor-cancel").header("X-Ticket-Secret", secret)).andReturn();
        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(200);
        assertThat((String) field(cancelled, "$.state")).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, ticketId)).isEqualTo("cancelled");
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalTicketAndAnIdempotencyKeyIsRequired() throws Exception {
        Setup s = setup("HR", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        String host = hostSystemToken(admin, s.site());
        String key = UUID.randomUUID().toString();

        MvcResult first = issue(host, key, s.service());
        MvcResult replay = issue(host, key, s.service());
        assertThat(status(first)).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ?", Integer.class, s.service())).isEqualTo(1);

        MvcResult missingKey = issue(host, null, s.service());
        assertThat(status(missingKey)).isEqualTo(400);
        assertThat(errorCode(missingKey)).isEqualTo("validation_failed");
    }

    // ---- §20: same permission checks and rate limits as other principals; no privileged internal path -------------

    @Test
    void onlyAHostSystemTokenMayCallTheHostTicketEndpointAndNeverOutsideItsOwnSiteScope() throws Exception {
        Setup mine = setup("PA", "[\"mobile\"]");
        Setup theirs = setup("PB", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        String host = hostSystemToken(admin, mine.site());
        String reception = staffToken(Role.RECEPTION_OPERATOR, mine.site());

        assertThat(status(issue(host, UUID.randomUUID().toString(), theirs.service())))
                .as("a service account cannot issue for a service outside the sites it was scoped to").isEqualTo(403);
        assertThat(status(issue(reception, UUID.randomUUID().toString(), mine.service())))
                .as("staff tokens cannot call the host-system-only endpoint").isEqualTo(403);
        assertThat(status(issue(null, UUID.randomUUID().toString(), mine.service()))).isEqualTo(401);

        assertThat(status(issue(host, UUID.randomUUID().toString(), mine.service()))).isEqualTo(201);
    }

    @Test
    void aServiceStillNeedsTheMobileChannelEnabledBeforeAHostSystemMayIssueToIt() throws Exception {
        Setup s = setup("CH", "[\"reception\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        String host = hostSystemToken(admin, s.site());

        MvcResult refused = issue(host, UUID.randomUUID().toString(), s.service());

        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("channel_not_allowed");
    }

    // ---- FR-INT-030: book an appointment and cancel it, through the same permission-checked endpoint staff use -----

    @Test
    void aHostSystemBooksAnAppointmentAndCancelsItThroughTheSameEndpointStaffUse() throws Exception {
        Setup s = setup("AP", "[\"mobile\"]");
        String admin = staffToken(Role.ORG_ADMIN);
        MvcResult template = call(
                put("/api/v1/appointment-templates/service/" + s.service()), admin,
                "{\"items\":[{\"weekday\":1,\"start\":\"09:00\",\"end\":\"10:00\",\"slot_minutes\":30,\"capacity\":2}]}");
        assertThat(status(template)).as(body(template)).isEqualTo(200);
        UUID visitor = newVisitor("Karim", "01700000001");
        String host = hostSystemToken(admin, s.site());
        String nextMonday = nextMonday();

        MvcResult booked = call(
                post("/api/v1/appointments"), host,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"phone\",\"visitor_id\":\"%s\"}",
                        s.service(), nextMonday, visitor));
        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        assertThat((String) field(booked, "$.state")).isEqualTo("booked");
        UUID appointmentId = UUID.fromString(field(booked, "$.id"));
        assertThat(jdbc.queryForObject("SELECT source FROM appointment WHERE id = ?", String.class, appointmentId)).isEqualTo("phone");

        MvcResult cancelled = call(delete("/api/v1/appointments/" + appointmentId), host, null);
        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT state FROM appointment WHERE id = ?", String.class, appointmentId)).isEqualTo("cancelled");
    }

    /** The Monday at least a week out from "now", in {@code yyyy-MM-dd}, so the booking is always in the future
     * regardless of when this suite runs (this class uses the real system clock, unlike {@code AppointmentBookingIT}). */
    private static String nextMonday() {
        java.time.LocalDate date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).plusWeeks(1);
        while (date.getDayOfWeek() != java.time.DayOfWeek.MONDAY) date = date.plusDays(1);
        return date.toString();
    }
}
