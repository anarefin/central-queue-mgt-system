package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
 * Ticket 49 against real PostgreSQL (SRS §16, FR-RPT-003/004/006/007): CSV/XLSX/PDF export of the detailed token
 * report, inline under the row threshold and as a background job over it, with FR-RPT-006's header block, FR-RPT-007's
 * separate PII permission gate and audit entry, and FR-RPT-004's async job status/download.
 *
 * <p>{@code qms.reporting.export.async-threshold-rows} is set to {@code 1} for this whole class (see {@link
 * #properties}), so a filter matching exactly one row exercises the inline path and one matching two exercises the
 * background one, without a 50,000-row fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, ReportExportIT.Clocks.class})
class ReportExportIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");

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
        registry.add("qms.reporting.export.async-threshold-rows", () -> "1");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-report-export");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired ReportingRefreshScheduler refreshScheduler;
    @Autowired ReportExportJobWorker exportWorker;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        jdbc.update("UPDATE reporting.refresh_watermark SET last_recorded_at = NULL");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures (the same shape ReportingIT's own fixtures use) ------------------------------------------------

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

    private MvcResult export(Staff who, String key, String json) throws Exception {
        return call(post("/api/v1/reports/" + key + "/export"), who == null ? null : who.token(), json == null ? "{}" : json);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(MvcResult result) {
        return result.getResponse().getContentAsByteArray();
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    // ---- CSV: inline, header block, raw values (FR-RPT-003, FR-RPT-006) --------------------------------------------

    @Test
    void csvExportIsInlineUnderTheThresholdAndCarriesTheHeaderBlock() throws Exception {
        World w = world();
        UUID visitorId = visitor("V-100", "Rahim", "vip");
        UUID ticket = issue(w.service(), visitorId);
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult result = export(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\",\"format\":\"csv\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("detailed-token.csv");

        String csv = new String(bytes(result), StandardCharsets.UTF_8);
        assertThat(csv).as("FR-RPT-006 header block").contains("\"Report\",\"detailed-token\"").contains("\"Generated by\"").contains("\"Filters\"").contains("site_id=" + w.site());
        assertThat(csv).as("column headers").contains("\"Token\",\"Visitor code\",\"Visitor name\"");
        assertThat(csv).as("a raw ISO instant, not a formatted display date").contains("Z\"");
        assertThat(csv).contains("V-100").contains("Rahim").contains(ticketToken(ticket));
    }

    // ---- XLSX: inline, opens as a real workbook with the same rows -----------------------------------------------

    @Test
    void xlsxExportOpensWithTheHeaderBlockAndDataRow() throws Exception {
        World w = world();
        UUID visitorId = visitor("V-200", "Karim", "senior");
        issue(w.service(), visitorId);
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult result = export(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\",\"format\":\"xlsx\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).contains("spreadsheetml");

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes(result)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Report");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("detailed-token");
            Row header = sheet.getRow(5);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Token");
            Row dataRow = sheet.getRow(6);
            assertThat(dataRow.getCell(1).getStringCellValue()).isEqualTo("V-200");
            assertThat(dataRow.getCell(2).getStringCellValue()).isEqualTo("Karim");
        }
    }

    // ---- PDF: inline, a real document whose extracted text carries the header block and a data row -----------------

    @Test
    void pdfExportOpensWithTheHeaderBlockAndDataRow() throws Exception {
        World w = world();
        UUID visitorId = visitor("V-300", "Salma", "general");
        issue(w.service(), visitorId);
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult result = export(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\",\"format\":\"pdf\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(bytes(result)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        try (var document = Loader.loadPDF(bytes(result))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Report").contains("detailed-token").contains("Token").contains("V-300");
        }
    }

    // ---- async: over the threshold, queued then polled to done with a working download link (FR-RPT-004) -----------

    @Test
    void overTheThresholdIsQueuedThenDownloadableOnceTheWorkerFinishesIt() throws Exception {
        World w = world();
        issue(w.service(), visitor("V-1", "One", "general"));
        issue(w.service(), visitor("V-2", "Two", "general"));
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        refreshScheduler.tick();

        MvcResult queued = export(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\",\"format\":\"csv\"}");
        assertThat(status(queued)).as(body(queued)).isEqualTo(202);
        String jobId = field(queued, "$.id");
        assertThat((String) field(queued, "$.status")).isEqualTo("queued");

        MvcResult beforeWorker = call(get("/api/v1/reports/jobs/" + jobId), admin.token(), null);
        assertThat((String) field(beforeWorker, "$.status")).isEqualTo("queued");

        exportWorker.tick();

        MvcResult afterWorker = call(get("/api/v1/reports/jobs/" + jobId), admin.token(), null);
        assertThat(status(afterWorker)).as(body(afterWorker)).isEqualTo(200);
        assertThat((String) field(afterWorker, "$.status")).isEqualTo("done");
        assertThat(((Number) field(afterWorker, "$.row_count")).intValue()).isEqualTo(2);
        assertThat((String) field(afterWorker, "$.expires_at")).isNotNull();

        MvcResult download = call(get("/api/v1/reports/jobs/" + jobId + "/download"), admin.token(), null);
        assertThat(status(download)).as(body(download)).isEqualTo(200);
        String csv = new String(bytes(download), StandardCharsets.UTF_8);
        assertThat(csv).contains("V-1").contains("V-2");

        // FR-RPT-004: an expired link answers not_found, indistinguishable from a job that never existed.
        jdbc.update("UPDATE reporting.export_job SET expires_at = ? WHERE id = ?::uuid", java.sql.Timestamp.from(BASE.minusSeconds(3600)), jobId);
        MvcResult expired = call(get("/api/v1/reports/jobs/" + jobId + "/download"), admin.token(), null);
        assertThat(status(expired)).isEqualTo(404);
    }

    // ---- FR-RPT-007 / FR-SEC-040: audited, and permission-checked server-side --------------------------------------

    @Test
    void everyExportIsAuditedAndOnlyReportRunExportRolesMayCallIt() throws Exception {
        World w = world();
        issue(w.service(), visitor("V-9", "Nine", "general"));
        Staff admin = staff(Role.ORG_ADMIN, w.site());
        Staff agent = staff(Role.AGENT, w.site());
        refreshScheduler.tick();

        long before = auditCount();
        MvcResult ok = export(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\",\"format\":\"csv\"}");
        assertThat(status(ok)).as(body(ok)).isEqualTo(200);
        assertThat(auditCount()).as("FR-RPT-007: written to the audit log").isEqualTo(before + 1);
        String afterJson = jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'report.exported' ORDER BY occurred_at DESC LIMIT 1", String.class);
        assertThat(afterJson).contains("\"contains_pii\": true").contains("\"report_key\": \"detailed-token\"");

        assertThat(status(export(agent, "detailed-token", "{\"format\":\"csv\"}"))).as("an Agent's own reach does not cover exporting reports").isEqualTo(403);
        assertThat(status(export(null, "detailed-token", "{\"format\":\"csv\"}"))).isEqualTo(401);
        assertThat(status(export(admin, "no-such-report", "{\"format\":\"csv\"}"))).isEqualTo(404);
        assertThat(status(export(admin, "detailed-token", "{\"format\":\"not-a-format\"}"))).isEqualTo(400);
    }

    private long auditCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'report.exported'", Long.class);
        return count == null ? 0 : count;
    }

    private String ticketToken(UUID ticketId) {
        return jdbc.queryForObject("SELECT token_number FROM ticket WHERE id = ?", String.class, ticketId);
    }
}
