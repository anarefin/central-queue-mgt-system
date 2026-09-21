package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * Ticket 53 against real PostgreSQL (SRS §16.3, §25.4-25.5; FR-RPT-021/022/023, FR-SEC-032/043): the retention
 * policy API, the nightly purge sweep ({@link RetentionPurgeRunner}), the nightly reporting extract ({@link
 * ReportingExtractRunner}) and the {@code bi} schema/role V44 provisions, driven the same "exercise the worker's
 * own tick, not the cron" way {@code ReportScheduleIT} already drives its own worker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, RetentionIT.Clocks.class})
class RetentionIT {

    private static final String PASSWORD = "Correct-Horse-9";
    private static final Path KEY_DIR = newTempDir("qms-keys-retention");
    private static final Path EXTRACT_DIR = newTempDir("qms-retention-extract");
    private static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");

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
        registry.add("qms.reporting.extract.cron", () -> "-");
        registry.add("qms.reporting.extract.location", EXTRACT_DIR::toString);
        registry.add("qms.retention.purge-cron", () -> "-");
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
    @Autowired MutableClock clock;
    @Autowired RetentionPurgeRunner purgeRunner;
    @Autowired ReportingExtractRunner extractRunner;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        jdbc.update("DELETE FROM reporting.ticket_fact");
        jdbc.update("UPDATE reporting.retention_policy SET retention_months = 24, mode = 'anonymize', updated_at = now(), updated_by = NULL WHERE data_class = 'ticket_detail'");
        jdbc.update("UPDATE reporting.retention_policy SET retention_months = 84, mode = 'purge', updated_at = now(), updated_by = NULL WHERE data_class = 'ticket_aggregate'");
        jdbc.update("UPDATE reporting.retention_policy SET retention_months = 24, mode = 'purge', updated_at = now(), updated_by = NULL WHERE data_class = 'audit'");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private record Staff(UUID id, String token) {}

    private Staff staff(Role role) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = UUID.randomUUID();
            String username = role.wire() + "-" + user;
            jdbc.update(
                    "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                    user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), "Admin " + user.toString().substring(0, 4), "en");
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, user);
                ps.setString(3, role.wire());
                ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
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

    private UUID insertTicketFact(Instant issuedAt, Instant anonymizedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reporting.ticket_fact (ticket_id, token_number, site_id, site_name, service_group_id, service_group_name,"
                        + " service_group_sort, service_id, service_name, service_sort, priority_class_id, priority_class_name, priority_class_sort,"
                        + " channel, state, is_chain_head, transfers, issued_at, refreshed_at, visitor_id, visitor_code, visitor_name, anonymized_at)"
                        + " VALUES (?, ?, ?, ?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'Outpatient', ?, '{\"en\":\"Consultation\"}'::jsonb, 'Consultation',"
                        + " ?, '{\"en\":\"Normal\"}'::jsonb, 'Normal', 'reception', 'closed', true, 0, ?, ?, ?, 'V-CODE', 'Karim', ?)",
                id, "OPD-" + id.toString().substring(0, 6), UUID.randomUUID(), "Main campus", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Timestamp.from(issuedAt), Timestamp.from(clock.instant()), UUID.randomUUID(),
                anonymizedAt == null ? null : Timestamp.from(anonymizedAt));
        return id;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Long.class, action);
        return count == null ? 0 : count;
    }

    private static Integer monthsOf(List<java.util.Map<String, Object>> items, String dataClass) {
        return items.stream()
                .filter(m -> dataClass.equals(m.get("data_class")))
                .map(m -> (Integer) m.get("retention_months"))
                .findFirst()
                .orElseThrow();
    }

    // ---- retention policy API (FR-SEC-032) --------------------------------------------------------------------

    @Test
    void defaultPoliciesAreListedAndOnlySystemOrOrgAdminMayViewOrChangeThem() throws Exception {
        Staff admin = staff(Role.ORG_ADMIN);
        Staff teamAdmin = staff(Role.TEAM_ADMIN);

        MvcResult listed = call(get("/api/v1/retention/policies"), admin.token(), null);
        assertThat(status(listed)).as(body(listed)).isEqualTo(200);
        List<java.util.Map<String, Object>> items = JsonPath.read(body(listed), "$.items");
        assertThat(items).extracting(m -> m.get("data_class")).containsExactlyInAnyOrder("ticket_detail", "ticket_aggregate", "audit");
        assertThat(monthsOf(items, "ticket_detail")).isEqualTo(24);
        assertThat(monthsOf(items, "ticket_aggregate")).isEqualTo(84);
        assertThat(monthsOf(items, "audit")).isEqualTo(24);

        // Team Admin has reports:run_export but not audit:read: refused (§MANAGE, the same "stricter" gate
        // DomainReportService already applies to the `audit` report key).
        assertThat(status(call(get("/api/v1/retention/policies"), teamAdmin.token(), null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/retention/policies"), null, null))).isEqualTo(401);
        assertThat(status(call(put("/api/v1/retention/policies/ticket_detail"), teamAdmin.token(), "{\"retention_months\":12}"))).isEqualTo(403);
    }

    @Test
    void updatingAPolicyValidatesEveryFieldAndIsAudited() throws Exception {
        Staff admin = staff(Role.ORG_ADMIN);
        long before = auditCount("retention.policy_changed");

        assertThat(status(call(put("/api/v1/retention/policies/no-such-class"), admin.token(), "{\"retention_months\":12}")))
                .as("an unknown data class is not_found").isEqualTo(404);
        assertThat(status(call(put("/api/v1/retention/policies/ticket_detail"), admin.token(), "{}")))
                .as("retention_months is required").isEqualTo(400);
        assertThat(status(call(put("/api/v1/retention/policies/ticket_detail"), admin.token(), "{\"retention_months\":0}")))
                .as("retention_months must be at least 1").isEqualTo(400);
        assertThat(status(call(put("/api/v1/retention/policies/ticket_detail"), admin.token(), "{\"retention_months\":12,\"mode\":\"forever\"}")))
                .as("an unknown mode is validation_failed").isEqualTo(400);
        assertThat(status(call(put("/api/v1/retention/policies/ticket_aggregate"), admin.token(), "{\"retention_months\":90,\"mode\":\"anonymize\"}")))
                .as("mode is not configurable for ticket_aggregate").isEqualTo(400);

        MvcResult updated = call(put("/api/v1/retention/policies/ticket_detail"), admin.token(), "{\"retention_months\":18,\"mode\":\"purge\"}");
        assertThat(status(updated)).as(body(updated)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(updated), "$.retention_months")).isEqualTo(18);
        assertThat((String) JsonPath.read(body(updated), "$.mode")).isEqualTo("purge");
        assertThat(auditCount("retention.policy_changed")).as("FR-SEC-040: a retention change is audited").isEqualTo(before + 1);
    }

    // ---- purge sweep (FR-RPT-021/022, FR-SEC-032/043) ---------------------------------------------------------

    @Test
    void ticketDetailPastRetentionIsReducedToAnAnonymisedAggregateByDefault() {
        UUID old = insertTicketFact(BASE.minus(Duration.ofDays(31 * 25)), null); // ~25 months old
        UUID recent = insertTicketFact(BASE.minus(Duration.ofDays(10)), null);
        long before = auditCount("retention.purge_run");

        RetentionPurgeSummary summary = purgeRunner.tick();

        assertThat(summary.ticketDetailAnonymized()).isEqualTo(1);
        assertThat(summary.ticketDetailPurged()).isZero();
        var oldRow = jdbc.queryForMap("SELECT visitor_id, visitor_code, visitor_name, anonymized_at FROM reporting.ticket_fact WHERE ticket_id = ?", old);
        assertThat(oldRow.get("visitor_id")).isNull();
        assertThat(oldRow.get("visitor_code")).isNull();
        assertThat(oldRow.get("visitor_name")).isNull();
        assertThat(oldRow.get("anonymized_at")).isNotNull();
        var recentRow = jdbc.queryForMap("SELECT visitor_name, anonymized_at FROM reporting.ticket_fact WHERE ticket_id = ?", recent);
        assertThat(recentRow.get("visitor_name")).isEqualTo("Karim");
        assertThat(recentRow.get("anonymized_at")).isNull();
        assertThat(auditCount("retention.purge_run")).as("FR-SEC-032: the purge job logs what it removed, in aggregate").isEqualTo(before + 1);
    }

    @Test
    void ticketDetailPastRetentionIsPurgedOutrightWhenTheClientChoosesThatMode() throws Exception {
        Staff admin = staff(Role.ORG_ADMIN);
        assertThat(status(call(put("/api/v1/retention/policies/ticket_detail"), admin.token(), "{\"retention_months\":24,\"mode\":\"purge\"}")))
                .isEqualTo(200);
        UUID old = insertTicketFact(BASE.minus(Duration.ofDays(31 * 25)), null);

        RetentionPurgeSummary summary = purgeRunner.tick();

        assertThat(summary.ticketDetailPurged()).isEqualTo(1);
        assertThat(summary.ticketDetailAnonymized()).isZero();
        Integer remaining = jdbc.queryForObject("SELECT count(*) FROM reporting.ticket_fact WHERE ticket_id = ?", Integer.class, old);
        assertThat(remaining).isZero();
    }

    @Test
    void anAnonymisedAggregateSurvivesUntilItsOwnLongerRetentionPassesThenIsPurged() {
        Instant longExpired = BASE.minus(Duration.ofDays(31 * 85)); // ~85 months: past the 84-month aggregate default
        Instant stillWithinAggregateWindow = BASE.minus(Duration.ofDays(31 * 30)); // ~30 months: an anonymised row that must survive
        UUID expired = insertTicketFact(longExpired, longExpired.plus(Duration.ofDays(1)));
        UUID surviving = insertTicketFact(stillWithinAggregateWindow, stillWithinAggregateWindow.plus(Duration.ofDays(1)));

        RetentionPurgeSummary summary = purgeRunner.tick();

        assertThat(summary.ticketAggregatePurged()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.ticket_fact WHERE ticket_id = ?", Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reporting.ticket_fact WHERE ticket_id = ?", Integer.class, surviving))
                .as("FR-RPT-022: an aggregate survives detail purge until its own, longer retention passes").isEqualTo(1);
    }

    @Test
    void auditLogPastItsOwnIndependentRetentionIsPurgedButTheAppendOnlyGuardStillBlocksAnyOtherDelete() {
        UUID staleMarker = UUID.randomUUID();
        UUID freshMarker = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO audit_log (id, action, entity, entity_id, occurred_at) VALUES (?, 'test.stale', 'x', ?, ?)",
                UUID.randomUUID(), staleMarker, Timestamp.from(BASE.minus(Duration.ofDays(31 * 25))));
        jdbc.update(
                "INSERT INTO audit_log (id, action, entity, entity_id, occurred_at) VALUES (?, 'test.fresh', 'x', ?, ?)",
                UUID.randomUUID(), freshMarker, Timestamp.from(BASE.minus(Duration.ofDays(5))));

        RetentionPurgeSummary summary = purgeRunner.tick();

        assertThat(summary.auditPurged()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ?", Integer.class, staleMarker)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ?", Integer.class, freshMarker)).isEqualTo(1);

        // The purge's own transaction-local flag is gone the instant it committed: every ordinary path is still refused.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", freshMarker))
                .hasMessageContaining("append-only");
    }

    // ---- nightly extract (FR-INT-060) and BI access (FR-RPT-023) ------------------------------------------------

    @Test
    void theNightlyExtractWritesACsvFileToTheConfiguredLocationAndIsAudited() throws Exception {
        insertTicketFact(BASE.minus(Duration.ofDays(1)), null);
        long before = auditCount("reporting.extract_generated");

        long rowCount = extractRunner.tick();

        assertThat(rowCount).isEqualTo(1);
        Path file = EXTRACT_DIR.resolve("ticket_fact-" + BASE.atZone(ZoneOffset.UTC).toLocalDate().toString().replace("-", "") + ".csv");
        assertThat(Files.exists(file)).as("the extract file exists at the configured location").isTrue();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2); // header + one row
        assertThat(lines.getFirst()).contains("token_number");
        assertThat(lines.getFirst()).doesNotContain("visitor_name", "visitor_code", "visitor_id");
        assertThat(auditCount("reporting.extract_generated")).isEqualTo(before + 1);
    }

    @Test
    void theBiViewExcludesDirectVisitorIdentifiersAndTheReaderRoleCanReadIt() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'bi' AND table_name = 'ticket_fact_v1'", String.class);
        assertThat(columns).contains("visitor_category").doesNotContain("visitor_id", "visitor_code", "visitor_name");

        Boolean roleExists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qms_bi_reader')", Boolean.class);
        assertThat(roleExists).isTrue();
        Boolean canUseSchema = jdbc.queryForObject("SELECT has_schema_privilege('qms_bi_reader', 'bi', 'USAGE')", Boolean.class);
        Boolean canSelect = jdbc.queryForObject("SELECT has_table_privilege('qms_bi_reader', 'bi.ticket_fact_v1', 'SELECT')", Boolean.class);
        assertThat(canUseSchema).as("FR-RPT-023: the BI role can be provisioned to read the stable view layer").isTrue();
        assertThat(canSelect).isTrue();
    }
}
