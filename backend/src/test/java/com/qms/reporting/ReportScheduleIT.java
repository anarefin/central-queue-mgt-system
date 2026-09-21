package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
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
 * Ticket 52 against real PostgreSQL (SRS §16, FR-RPT-005): create/list/update/delete a schedule over HTTP, run a
 * due one through {@link ReportScheduleRunner} directly (the same "drive the worker's own tick, not the cron" shape
 * {@code ReportExportIT} already uses for its own background worker) and check the email actually left over a real
 * SMTP conversation, the delivery log recorded it, the schedule's own {@code next_run_at} advanced, and every
 * protected action is both permission-checked and audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, ReportScheduleIT.Clocks.class})
class ReportScheduleIT {

    private static final String PASSWORD = "Correct-Horse-9";
    private static final Path KEY_DIR = newKeyDir();
    private static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");
    private static final FakeSmtpServer SERVER = FakeSmtpServer.start();

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
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
        registry.add("qms.queue.remote-arrival-check-cron", () -> "-");
        registry.add("qms.reporting.refresh-cron", () -> "-");
        registry.add("qms.reporting.export.poll-cron", () -> "-");
        registry.add("qms.reporting.schedule.poll-cron", () -> "-");
        registry.add("qms.notification.email.host", () -> "127.0.0.1");
        registry.add("qms.notification.email.port", SERVER::port);
        registry.add("qms.notification.email.starttls", () -> "false");
        registry.add("qms.notification.email.from", () -> "noreply@qms.local");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-report-schedule");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired ReportingRefreshScheduler refreshScheduler;
    @Autowired ReportScheduleRunner runner;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        jdbc.update("UPDATE reporting.refresh_watermark SET last_recorded_at = NULL");
        SERVER.received().clear();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures (the same shape ReportExportIT's own fixtures use) -----------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "R-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                service, group);
        return new World(site, zone, group, service);
    }

    private UUID visitor(String code, String name, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, ?, ?, now())", id, code, name, category);
        return id;
    }

    private Staff staff(Role role, UUID site) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = UUID.randomUUID();
            String username = role.wire() + "-" + user;
            jdbc.update(
                    "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                    user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), "Admin " + user.toString().substring(0, 4), "en");
            UUID[] sites = site == null ? new UUID[0] : new UUID[] {site};
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, user);
                ps.setString(3, role.wire());
                ps.setArray(4, connection.createArrayOf("uuid", sites));
                ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                return ps;
            });
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private UUID issue(UUID service, UUID visitorId) {
        return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null, null, visitorId, false, null, null, null)).id();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
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

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Long.class, action);
        return count == null ? 0 : count;
    }

    // ---- create: dry-run validation, audited, permission-checked (FR-RPT-005) -----------------------------------

    @Test
    void aScheduleIsCreatedAuditedAndOnlyReportRunExportAndPiiRolesMayCreateOne() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        Staff reception = staff(Role.RECEPTION_OPERATOR, w.site());
        refreshScheduler.tick();

        long before = auditCount("report_schedule.created");
        MvcResult created = call(
                post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"],"
                        + "\"filter\":{\"site_id\":\"" + w.site() + "\"}}");
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        assertThat((String) field(created, "$.report_key")).isEqualTo("detailed-token");
        assertThat((String) field(created, "$.cadence")).isEqualTo("daily");
        assertThat((Boolean) field(created, "$.enabled")).isTrue();
        assertThat((String) field(created, "$.next_run_at")).isNotNull();
        assertThat(auditCount("report_schedule.created")).as("FR-SEC-042: schedule creation is audited").isEqualTo(before + 1);

        // Reception has reports:run_export but not visitor_pii:view: forbidden to create a schedule (§MANAGE).
        assertThat(status(call(
                post("/api/v1/reports/schedules"), reception.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"]}")))
                .isEqualTo(403);
        assertThat(status(call(post("/api/v1/reports/schedules"), null, "{\"report_key\":\"detailed-token\"}"))).isEqualTo(401);
    }

    @Test
    void createValidatesTheKeyTheCadenceTheFormatAndEveryRecipient() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        assertThat(status(call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"no-such-report\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"]}")))
                .as("an unknown report key is not_found").isEqualTo(404);
        assertThat(status(call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"yearly\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"]}")))
                .as("an unknown cadence is validation_failed").isEqualTo(400);
        assertThat(status(call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"docx\",\"recipients\":[\"ops@example.com\"]}")))
                .as("an unknown format is validation_failed").isEqualTo(400);
        assertThat(status(call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[]}")))
                .as("no recipients is validation_failed").isEqualTo(400);
        assertThat(status(call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"not-an-email\"]}")))
                .as("an invalid email is validation_failed").isEqualTo(400);
    }

    @Test
    void createRefusesAKeyTheCallersOwnRoleCannotRunEvenThoughTheyCanRunEveryOtherKey() throws Exception {
        World w = world();
        Staff teamAdmin = staff(Role.TEAM_ADMIN, w.site());

        // The audit report key is System/Org Admin only (AUDIT_READ); a Team Admin has reports:run_export and
        // visitor_pii:view (so passes ReportScheduleService.MANAGE) but not AUDIT_READ, so the dry run inside
        // create() itself is refused — caught at creation, not silently at the schedule's first tick.
        MvcResult refused = call(post("/api/v1/reports/schedules"), teamAdmin.token(),
                "{\"report_key\":\"audit\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"]}");
        assertThat(status(refused)).as(body(refused)).isEqualTo(403);
    }

    // ---- list / get / update / delete -----------------------------------------------------------------------

    @Test
    void listGetUpdateAndDeleteWorkAndAreAudited() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult created = call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"]}");
        String id = field(created, "$.id");

        MvcResult listed = call(get("/api/v1/reports/schedules"), admin.token(), null);
        assertThat(status(listed)).isEqualTo(200);
        java.util.List<String> ids = field(listed, "$.items[*].id");
        assertThat(ids).contains(id);

        MvcResult got = call(get("/api/v1/reports/schedules/" + id), admin.token(), null);
        assertThat(status(got)).isEqualTo(200);
        assertThat((String) field(got, "$.id")).isEqualTo(id);

        long updatedBefore = auditCount("report_schedule.updated");
        MvcResult updated = call(put("/api/v1/reports/schedules/" + id), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"weekly\",\"format\":\"xlsx\",\"recipients\":[\"ops@example.com\",\"second@example.com\"],\"enabled\":true}");
        assertThat(status(updated)).as(body(updated)).isEqualTo(200);
        assertThat((String) field(updated, "$.cadence")).isEqualTo("weekly");
        assertThat((String) field(updated, "$.format")).isEqualTo("xlsx");
        assertThat(auditCount("report_schedule.updated")).isEqualTo(updatedBefore + 1);

        long deletedBefore = auditCount("report_schedule.deleted");
        assertThat(status(call(delete("/api/v1/reports/schedules/" + id), admin.token(), null))).isEqualTo(204);
        assertThat(auditCount("report_schedule.deleted")).isEqualTo(deletedBefore + 1);
        assertThat(status(call(get("/api/v1/reports/schedules/" + id), admin.token(), null))).isEqualTo(404);
    }

    // ---- the worker's own tick: generates, emails, logs the delivery, advances next_run_at ------------------

    @Test
    void aDueScheduleIsEmailedWithTheReportAttachedAndTheDeliveryIsLogged() throws Exception {
        World w = world();
        UUID v1 = visitor("V-1", "Rahim", "vip");
        UUID v2 = visitor("V-2", "Karim", "senior");
        issue(w.service(), v1);
        issue(w.service(), v2);
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult created = call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"],"
                        + "\"filter\":{\"site_id\":\"" + w.site() + "\"}}");
        assertThat(status(created)).as(body(created)).isEqualTo(200);
        String id = field(created, "$.id");

        // The tickets were issued at BASE; the tick's own window is [now - 1 day, now), an exclusive upper bound
        // (DetailedTokenReportReads: "issued_at < ?"), so the clock has to move past BASE for them to fall inside
        // the daily window the worker computes when it fires.
        clock.set(BASE.plusSeconds(120));

        // Force it due: the worker itself computes the next next_run_at from the cadence at tick time, never from
        // a client-supplied value, so forcing it into the past is the only way to make it fire without waiting.
        Instant forcedDue = clock.instant().minusSeconds(1);
        jdbc.update("UPDATE reporting.report_schedule SET next_run_at = ? WHERE id = ?::uuid", java.sql.Timestamp.from(forcedDue), id);

        int fired = runner.tick();
        assertThat(fired).isEqualTo(1);

        FakeSmtpServer.Received received = SERVER.received().poll(5, TimeUnit.SECONDS);
        assertThat(received).as("the schedule's own report was emailed").isNotNull();
        assertThat(received.from()).isEqualTo("noreply@qms.local");
        assertThat(received.to()).isEqualTo("ops@example.com");
        assertThat(received.data()).contains("Subject:").contains("detailed-token");
        assertThat(received.data()).as("the rendered report is attached").contains("detailed-token.csv").contains("attachment");

        MvcResult after = call(get("/api/v1/reports/schedules/" + id), admin.token(), null);
        assertThat((String) field(after, "$.last_run_at")).isNotNull();
        Instant secondNextRun = Instant.parse((String) field(after, "$.next_run_at"));
        assertThat(secondNextRun).as("next_run_at advanced past the forced-due value the tick just consumed").isAfter(forcedDue);

        MvcResult deliveries = call(get("/api/v1/reports/schedules/" + id + "/deliveries"), admin.token(), null);
        assertThat(status(deliveries)).isEqualTo(200);
        assertThat((String) field(deliveries, "$.items[0].status")).isEqualTo("sent");
        assertThat((String) field(deliveries, "$.items[0].recipient")).isEqualTo("ops@example.com");
        assertThat(((Number) field(deliveries, "$.items[0].row_count")).intValue()).isEqualTo(2);
    }

    @Test
    void aReportGenerationFailureIsLoggedAsOneFailedDeliveryAndTheScheduleStillAdvances() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult created = call(post("/api/v1/reports/schedules"), admin.token(),
                "{\"report_key\":\"detailed-token\",\"cadence\":\"daily\",\"format\":\"csv\",\"recipients\":[\"ops@example.com\"],"
                        + "\"filter\":{\"site_id\":\"" + w.site() + "\"}}");
        String id = field(created, "$.id");

        // A corrupted filter (valid jsonb, but not the object shape ReportScheduleFilter needs) makes the report
        // itself unrunnable — the cleanest way to exercise "generation fails before any recipient is attempted"
        // without depending on any particular report key's own runtime behaviour.
        Instant forcedDue = BASE.minusSeconds(1);
        jdbc.update("UPDATE reporting.report_schedule SET next_run_at = ?, filter = '\"broken\"'::jsonb WHERE id = ?::uuid", java.sql.Timestamp.from(forcedDue), id);

        int fired = runner.tick();
        assertThat(fired).isEqualTo(1);
        assertThat(SERVER.received().poll(1, TimeUnit.SECONDS)).as("nothing was sent once generation itself failed").isNull();

        MvcResult deliveries = call(get("/api/v1/reports/schedules/" + id + "/deliveries"), admin.token(), null);
        assertThat((String) field(deliveries, "$.items[0].status")).isEqualTo("failed");
        assertThat((Object) field(deliveries, "$.items[0].recipient")).as("no recipient was ever attempted").isNull();

        // GET .../{id} itself would now also fail to parse the deliberately-corrupted filter, so next_run_at is
        // read straight from the database instead of through that endpoint.
        Instant nextRunAt = jdbc.queryForObject(
                "SELECT next_run_at FROM reporting.report_schedule WHERE id = ?::uuid", java.sql.Timestamp.class, id).toInstant();
        assertThat(nextRunAt).as("a broken schedule still advances past the forced-due value the tick just consumed").isAfter(forcedDue);
    }

    // ---- a minimal fake SMTP relay: just enough of RFC 5321 for a plain, unauthenticated, non-TLS send,
    // the same shape com.qms.notification.EmailChannelIT's own fake server already uses. --------------------------

    static final class FakeSmtpServer {
        private static final Pattern ADDRESS = Pattern.compile("<([^>]*)>");

        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final BlockingQueue<Received> received = new ArrayBlockingQueue<>(10);

        record Received(String from, String to, String data) {}

        private FakeSmtpServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.acceptThread = new Thread(this::acceptLoop, "fake-smtp-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        static FakeSmtpServer start() {
            try {
                ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                return new FakeSmtpServer(socket);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        BlockingQueue<Received> received() {
            return received;
        }

        void stop() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    handle(socket);
                } catch (IOException e) {
                    return; // socket closed on shutdown
                }
            }
        }

        private void handle(Socket socket) {
            try (socket;
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false)) {
                reply(out, "220 localhost fake SMTP ready");
                String from = null;
                String to = null;
                String line;
                while ((line = in.readLine()) != null) {
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        reply(out, "250 localhost");
                    } else if (upper.startsWith("MAIL FROM")) {
                        from = address(line);
                        reply(out, "250 OK");
                    } else if (upper.startsWith("RCPT TO")) {
                        to = address(line);
                        reply(out, "250 OK");
                    } else if (upper.startsWith("DATA")) {
                        reply(out, "354 Start mail input; end with <CRLF>.<CRLF>");
                        StringBuilder data = new StringBuilder();
                        String dataLine;
                        while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                            data.append(dataLine.startsWith("..") ? dataLine.substring(1) : dataLine).append('\n');
                        }
                        received.add(new Received(from, to, data.toString()));
                        reply(out, "250 OK: queued");
                    } else if (upper.startsWith("QUIT")) {
                        reply(out, "221 Bye");
                        return;
                    } else if (upper.startsWith("RSET")) {
                        reply(out, "250 OK");
                    } else if (upper.startsWith("NOOP")) {
                        reply(out, "250 OK");
                    } else {
                        reply(out, "500 unrecognized command");
                    }
                }
            } catch (IOException ignored) {
                // client disconnected; nothing more to serve
            }
        }

        private static void reply(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        private static String address(String commandLine) {
            Matcher matcher = ADDRESS.matcher(commandLine);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}
