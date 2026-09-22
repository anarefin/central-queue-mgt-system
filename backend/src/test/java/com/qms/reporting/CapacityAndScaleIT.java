package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NFR-CAP-001 (50 sites / 500 counters / 2,000 staff), NFR-CAP-004 (five years of ticket history without breaching a
 * §23.1 query target) — ticket 61's acceptance suite.
 *
 * <p>NFR-CAP-002 (20,000 tickets/day, 60/minute peak, sustained) is measured by the k6 load test at
 * {@code deploy/loadtest/token-issuance.js} (§27.4), not here: a JVM integration test is the wrong tool for a
 * throughput claim over wall-clock minutes. NFR-CAP-003 (5,000 concurrent realtime subscribers) is measured at
 * {@code com.qms.platform.realtime.RealtimeCapacityIT}, scaled down for the reasons documented on that class.
 *
 * <p>Five full years at the true NFR-CAP-002 volume is 20,000 &times; 365 &times; 5 &asymp; 36.5 million rows — not a
 * size this sandbox can generate and query inside a CI-scale test run. The same trade-off the traceability matrix
 * already accepted for NFR-PERF-006 (ticket 48/49: "verified by index/query design rather than by literally timing a
 * fixture at scale") applies here: this test seeds a five-year-wide, still-substantial sample (100,000 rows, one row
 * every 26 minutes for five years, batch-inserted directly into {@code reporting.ticket_fact} — the report's own read
 * path, {@code DetailedTokenReportReads}, never sees how the rows arrived) and times the exact query shape §23.1
 * names: a 12-month, single-Site, 1,000-row page of the detailed token report. The date-range index
 * ({@code ticket_fact_site_issued_idx}, on {@code (site_id, issued_at)}) is what makes the cost independent of the
 * other four years sitting either side of the window, which is what "without query degradation" means in practice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class CapacityAndScaleIT {

    private static final String PASSWORD = "Correct-Horse-9";
    private static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-capacity");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---- NFR-CAP-004: five years of history, §23.1's 12-month/1,000-row/5s target still met ------------------------

    @Test
    void aTwelveMonthPageOfTheDetailedTokenReportStaysUnderFiveSecondsWithFiveYearsOfHistoryInTheTable() throws Exception {
        UUID site = newSite();
        UUID group = newGroup(site);
        UUID service = newService(group);
        UUID priorityClass = newPriorityClass();
        String admin = staffToken(Role.ORG_ADMIN, site);

        Instant now = Instant.now();
        Instant fiveYearsAgo = now.minus(5 * 365, ChronoUnit.DAYS);
        seedTicketFact(site, group, service, priorityClass, fiveYearsAgo, now, 100_000);

        Instant windowStart = now.minus(365, ChronoUnit.DAYS);
        String filter = "{\"site_id\":\"" + site + "\",\"from\":\"" + windowStart + "\",\"to\":\"" + now + "\",\"sort\":\"issued_at\",\"direction\":\"desc\",\"page\":0,\"size\":1000}";

        long started = System.nanoTime();
        MvcResult page = call(post("/api/v1/reports/detailed-token/run"), admin, filter);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(status(page)).as(body(page)).isEqualTo(200);
        assertThat(elapsed).as("NFR-PERF-005: a 1,000-row, 12-month page renders within 5 s, unaffected by four more years of history either side of the window").isLessThan(Duration.ofSeconds(5));
        List<String> rows = field(page, "$.rows[*].ticket_id");
        assertThat(rows).as("the page is full: the window holds far more than 1,000 rows at this seeded density").hasSize(1000);
    }

    // ---- NFR-CAP-001: 50 sites, 500 counters (10 each), 2,000 staff accounts ----------------------------------------

    @Test
    void anInstallationAtFiftySitesFiveHundredCountersAndTwoThousandStaffStaysFunctional() throws Exception {
        int siteCount = 50;
        int countersPerSite = 10; // 50 * 10 = 500
        int staffCount = 2000;

        UUID[] siteIds = new UUID[siteCount];
        UUID[] zoneIds = new UUID[siteCount];
        for (int i = 0; i < siteCount; i++) {
            siteIds[i] = newSite();
            zoneIds[i] = newZone(siteIds[i]);
        }

        List<Object[]> counterRows = new java.util.ArrayList<>();
        for (int i = 0; i < siteCount; i++) {
            for (int c = 0; c < countersPerSite; c++) {
                counterRows.add(new Object[] {UUID.randomUUID(), zoneIds[i], "Desk " + c});
            }
        }
        int[] counterCounts = jdbc.batchUpdate("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", counterRows);
        assertThat(java.util.Arrays.stream(counterCounts).sum()).isEqualTo(siteCount * countersPerSite);

        List<Object[]> userRows = new java.util.ArrayList<>();
        List<UUID> userIds = new java.util.ArrayList<>();
        String hash = new BCryptPasswordEncoder(4).encode(PASSWORD); // cost 4: 2,000 hashes is fixture setup, not the thing under test
        for (int i = 0; i < staffCount; i++) {
            UUID id = UUID.randomUUID();
            userIds.add(id);
            userRows.add(new Object[] {id, "cap-staff-" + i + "-" + id, hash, "Staff " + i, "en"});
        }
        int[] userCounts = jdbc.batchUpdate("INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)", userRows);
        assertThat(java.util.Arrays.stream(userCounts).sum()).isEqualTo(staffCount);

        jdbc.batchUpdate(
                "INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)",
                userIds,
                200,
                (ps, userId) -> {
                    UUID site = siteIds[Math.floorMod(userId.hashCode(), siteCount)];
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, userId);
                    ps.setString(3, Role.AGENT.wire());
                    ps.setArray(4, ps.getConnection().createArrayOf("uuid", new UUID[] {site}));
                    ps.setArray(5, ps.getConnection().createArrayOf("uuid", new UUID[0]));
                });

        // The install is still functional at this scale, not merely populated: the 2000th account can sign in.
        String lastUsername = (String) userRows.get(userRows.size() - 1)[1];
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + lastUsername + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(login)).as(body(login)).isEqualTo(200);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Capacity site', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "CAP-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newZone(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", id, site);
        return id;
    }

    private UUID newGroup(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", id, site);
        return id;
    }

    private UUID newService(UUID group) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group);
        return id;
    }

    private UUID newPriorityClass() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, created_at, updated_at) VALUES (?, '{\"en\":\"Normal\"}'::jsonb, 0, now(), now())", id);
        return id;
    }

    private String staffToken(Role role, UUID site) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(login)).as(body(login)).isEqualTo(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.access_token");
    }

    /** Batch-inserts {@code count} rows straight into the reporting store, spread evenly across {@code [from, to]}. */
    private void seedTicketFact(UUID site, UUID group, UUID service, UUID priorityClass, Instant from, Instant to, int count) {
        Random random = new Random(42);
        long spanSeconds = Duration.between(from, to).getSeconds();
        List<Object[]> rows = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant issuedAt = from.plusSeconds((long) ((double) i / count * spanSeconds));
            Instant closedAt = issuedAt.plusSeconds(600 + random.nextInt(1800));
            rows.add(new Object[] {
                UUID.randomUUID(), "A-" + (100000 + i), site, "Capacity site", group, "{\"en\":\"Outpatient\"}", "outpatient", service, "{\"en\":\"Consultation\"}",
                "consultation", priorityClass, "{\"en\":\"Normal\"}", "normal", "reception", "completed", true, 0, java.sql.Timestamp.from(issuedAt),
                java.sql.Timestamp.from(issuedAt.plusSeconds(300)), java.sql.Timestamp.from(issuedAt.plusSeconds(360)), java.sql.Timestamp.from(closedAt), 300,
                (int) Duration.between(issuedAt.plusSeconds(360), closedAt).getSeconds(), java.sql.Timestamp.from(Instant.now())
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO reporting.ticket_fact (ticket_id, token_number, site_id, site_name, service_group_id, service_group_name, service_group_sort,"
                        + " service_id, service_name, service_sort, priority_class_id, priority_class_name, priority_class_sort, channel, state, is_chain_head,"
                        + " transfers, issued_at, called_at, served_at, closed_at, wait_seconds, service_seconds, refreshed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rows);
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
}
