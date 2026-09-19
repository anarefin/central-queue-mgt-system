package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ticket 08 against real PostgreSQL with a clock the test moves: an Org Admin configures how Token numbers look and
 * when they reset per Service or Service group, and sequences restart at the site-local reset time, even when nothing
 * was running at that moment, with no duplicate numbers and prior days intact (FR-CFG-018, FR-CFG-019, FR-CFG-041,
 * FR-QUE-201, ADR-0010, UAT U10).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, NumberingIT.Clocks.class})
class NumberingIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 16:00 in Dhaka (UTC+6). */
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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-numbering");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired IssuanceService issuance;
    @Autowired SequenceBlocks sequences;
    @Autowired NumberingResets resets;
    @Autowired NumberingScheduler scheduler;
    @Autowired TransactionTemplate transactions;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    /** A Dhaka site with one group (prefix {@code G<p>}) and one reception service (prefix {@code p}). */
    private Setup setup(String prefix) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "N-" + site.toString().substring(0, 8));
        UUID group = newGroup(site, "G" + prefix);
        return new Setup(site, group, newService(group, prefix));
    }

    private UUID newGroup(UUID site, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, ?)", id, site, prefix);
        return id;
    }

    private UUID newService(UUID group, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, prefix);
        return id;
    }

    private String issue(UUID service) {
        return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null)).tokenNumber();
    }

    private List<String> issue(UUID service, int count) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) tokens.add(issue(service));
        return tokens;
    }

    private void at(String instant) {
        clock.set(Instant.parse(instant));
    }

    /** A signed-in token. It is minted at real time whatever the test clock says, because tokens are validated against the system clock. */
    private String token(Role role, UUID... sites) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            return login(role, sites);
        } finally {
            clock.set(testTime);
        }
    }

    private String login(Role role, UUID... sites) throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                user, role.wire() + "-" + user, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
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
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        return JsonPath.read(body(result), "$.access_token");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult putRule(String token, String kind, UUID id, String json) throws Exception {
        return call(put("/api/v1/" + kind + "/" + id + "/numbering-rule"), token, json);
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

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    private static String scopeKey(UUID site, String prefix) {
        return SequenceBlocks.scope(site, prefix);
    }

    private Map<String, Object> ledger(UUID site, String prefix, String resetKey) {
        return jdbc.queryForMap("SELECT triggered_by, period_start FROM numbering_reset WHERE scope_key = ? AND reset_key = ?", scopeKey(site, prefix), resetKey);
    }

    // ---- FR-CFG-018: the rule ----------------------------------------------------------------------------------

    @Test
    void aRuleSetsPrefixSourceSeparatorPaddingAndStartAndAServiceRuleBeatsItsGroups() throws Exception {
        Setup s = setup("A");
        String admin = token(Role.ORG_ADMIN, s.site());

        assertThat(issue(s.service())).as("no rule: the built-in default").isEqualTo("A-001");

        MvcResult group = putRule(admin, "service-groups", s.group(), "{\"prefix_source\":\"service_group\",\"sequence_start\":100,\"padding\":4,\"separator\":\"/\"}");
        assertThat(status(group)).as(body(group)).isEqualTo(200);
        assertThat((String) field(group, "$.rule.prefix_source")).isEqualTo("service_group");
        assertThat((Integer) field(group, "$.rule.padding")).isEqualTo(4);
        assertThat((String) field(group, "$.rule.reset_time")).isEqualTo("00:00");
        assertThat((String) field(group, "$.rule.reset_boundary")).isEqualTo("daily");
        assertThat(issue(s.service())).as("the group's prefix, separator and padding; the sequence of prefix GA starts at 100").isEqualTo("GA/0100");
        assertThat(issue(s.service())).isEqualTo("GA/0101");

        MvcResult own = putRule(admin, "services", s.service(), "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"VIP\",\"padding\":0,\"separator\":\"\",\"sequence_start\":5}");
        assertThat(status(own)).as(body(own)).isEqualTo(200);
        assertThat(issue(s.service())).as("the service's own rule beats its group's").isEqualTo("VIP5");
        assertThat(issue(s.service())).isEqualTo("VIP6");

        MvcResult priority = putRule(admin, "services", s.service(), "{\"prefix_source\":\"priority_class\"}");
        assertThat(status(priority)).isEqualTo(200);
        assertThat(issue(s.service())).as("no priority class exists yet, so the service's own prefix").isEqualTo("A-002");

        assertThat(status(call(delete("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null))).isEqualTo(200);
        assertThat(issue(s.service())).as("back to the group's rule").isEqualTo("GA/0102");
        assertThat(status(call(delete("/api/v1/service-groups/" + s.group() + "/numbering-rule"), admin, null))).isEqualTo(200);
        assertThat(issue(s.service())).as("back to the default, which continues that prefix's sequence").isEqualTo("A-003");
        assertThat(status(call(delete("/api/v1/service-groups/" + s.group() + "/numbering-rule"), admin, null))).as("nothing left to remove").isEqualTo(404);
    }

    @Test
    void theRuleCanBeReadListedAndIsRefusedWhenInvalid() throws Exception {
        Setup s = setup("B");
        String admin = token(Role.ORG_ADMIN, s.site());
        assertThat(status(call(get("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null))).isEqualTo(404);
        putRule(admin, "services", s.service(), "{\"reset_boundary\":\"weekly\",\"reset_time\":\"04:30\",\"padding\":2}");
        putRule(admin, "service-groups", s.group(), "{}");

        MvcResult read = call(get("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null);
        MvcResult list = call(get("/api/v1/sites/" + s.site() + "/numbering-rules"), admin, null);

        assertThat((String) field(read, "$.reset_boundary")).isEqualTo("weekly");
        assertThat((String) field(read, "$.reset_time")).isEqualTo("04:30");
        assertThat((List<String>) field(list, "$.items[*].scope_type")).containsExactlyInAnyOrder("service", "service_group");
        for (String bad : List.of("{\"padding\":7}", "{\"reset_boundary\":\"yearly\"}", "{\"reset_time\":\"25:00\"}", "{\"prefix_source\":\"fixed\"}", "{\"sequence_start\":-1}")) {
            MvcResult refused = putRule(admin, "services", s.service(), bad);
            assertThat(status(refused)).as(bad).isEqualTo(400);
            assertThat((String) field(refused, "$.error.code")).isEqualTo("validation_failed");
        }
        assertThat((Integer) field(call(get("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null), "$.padding")).as("a refused change stores nothing").isEqualTo(2);
        assertThat(status(putRule(admin, "services", UUID.randomUUID(), "{}"))).as("unknown service").isEqualTo(404);
    }

    @Test
    void everyNumberingActionIsCheckedOnTheServerAndScopedToTheCallersSites() throws Exception {
        Setup s = setup("C");
        Setup other = setup("D");
        String url = "/api/v1/services/" + s.service() + "/numbering-rule";
        for (Role role : List.of(Role.TEAM_ADMIN, Role.AGENT, Role.RECEPTION_OPERATOR)) {
            String token = token(role, s.site());
            assertThat(status(call(put(url), token, "{}"))).as(role + " set").isEqualTo(403);
            assertThat(status(call(get(url), token, null))).as(role + " read").isEqualTo(403);
            assertThat(status(call(delete(url), token, null))).as(role + " delete").isEqualTo(403);
            assertThat(status(call(get("/api/v1/services/" + s.service() + "/numbering-preview"), token, null))).as(role + " preview").isEqualTo(403);
            assertThat(status(call(get("/api/v1/sites/" + s.site() + "/numbering-rules"), token, null))).as(role + " list").isEqualTo(403);
        }
        assertThat(status(call(put(url), null, "{}"))).as("unauthenticated").isEqualTo(401);
        for (Role role : List.of(Role.SYSTEM_ADMIN, Role.ORG_ADMIN)) {
            assertThat(status(call(put(url), token(role, s.site()), "{}"))).as(role.wire()).isEqualTo(200);
        }
        String siteAdmin = token(Role.ORG_ADMIN, other.site());
        assertThat(status(call(put(url), siteAdmin, "{}"))).as("another site's Org Admin").isEqualTo(403);
        assertThat(status(call(get("/api/v1/services/" + s.service() + "/numbering-preview"), siteAdmin, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/numbering-rules"), siteAdmin, null))).isEqualTo(403);
    }

    @Test
    void changesToRulesAreAuditedWithBeforeAndAfterValues() throws Exception {
        Setup s = setup("E");
        String admin = token(Role.ORG_ADMIN, s.site());
        putRule(admin, "services", s.service(), "{\"padding\":4}");
        UUID rule = jdbc.queryForObject("SELECT id FROM numbering_rule WHERE scope_id = ?", UUID.class, s.service());
        putRule(admin, "services", s.service(), "{\"padding\":5}");
        putRule(admin, "services", s.service(), "{\"padding\":5}");
        call(delete("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null);

        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log WHERE entity = 'numbering_rule' AND entity_id = ? ORDER BY occurred_at, id", String.class, rule);
        assertThat(actions).as("an identical repeat changes nothing and writes nothing").containsExactlyInAnyOrder("numbering_rule.created", "numbering_rule.updated", "numbering_rule.deleted");
        Map<String, Object> updated = jdbc.queryForMap("SELECT before::text AS before, after::text AS after, actor_role FROM audit_log WHERE action = 'numbering_rule.updated' AND entity_id = ?", rule);
        assertThat((String) updated.get("before")).contains("\"padding\": 4");
        assertThat((String) updated.get("after")).contains("\"padding\": 5");
        assertThat(updated.get("actor_role")).isEqualTo("org_admin");
    }

    // ---- FR-CFG-041: changing a rule never renumbers -----------------------------------------------------------

    @Test
    void changingARuleNeverRenumbersIssuedTicketsAndWarnsHowManyAreWaiting() throws Exception {
        Setup s = setup("F");
        String admin = token(Role.ORG_ADMIN, s.site());
        assertThat(issue(s.service(), 2)).containsExactly("F-001", "F-002");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT id, token_number, sequence_no, reset_key FROM ticket WHERE service_id = ? ORDER BY sequence_no", s.service());

        MvcResult change = putRule(admin, "services", s.service(), "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"Z\",\"separator\":\"/\",\"padding\":5}");

        assertThat((Integer) field(change, "$.affected_waiting_tickets")).as("both tickets keep their numbers").isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id, token_number, sequence_no, reset_key FROM ticket WHERE service_id = ? ORDER BY sequence_no", s.service())).isEqualTo(before);
        assertThat(issue(s.service())).as("only tickets issued from now on follow the new rule").isEqualTo("Z/00001");
        MvcResult snapshot = call(get("/api/v1/queues/" + s.service()), admin, null);
        assertThat((List<String>) field(snapshot, "$.tickets[*].token_number")).containsExactlyInAnyOrder("F-001", "F-002", "Z/00001");

        MvcResult removed = call(delete("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null);
        assertThat((Integer) field(removed, "$.affected_waiting_tickets")).isEqualTo(3);
        assertThat(count("ticket", "service_id = ? AND token_number IN ('F-001', 'F-002', 'Z/00001')", s.service())).isEqualTo(3);
    }

    @Test
    void changingTheBoundaryPartWayThroughAPeriodNeverRepeatsANumber() throws Exception {
        Setup s = setup("G");
        // Monday 14 and Tuesday 15 September under a daily rule, then the rule becomes weekly on Wednesday.
        at("2026-09-14T05:00:00Z");
        assertThat(issue(s.service(), 2)).containsExactly("G-001", "G-002");
        at("2026-09-15T05:00:00Z");
        assertThat(issue(s.service(), 2)).as("a new day restarts").containsExactly("G-001", "G-002");
        at("2026-09-16T05:00:00Z");
        String midWeek = token(Role.ORG_ADMIN, s.site());
        assertThat(status(putRule(midWeek, "services", s.service(), "{\"prefix_source\":\"service\",\"reset_boundary\":\"weekly\"}"))).isEqualTo(200);

        assertThat(issue(s.service(), 2)).as("the weekly period began Monday, so it continues after what Monday and Tuesday used").containsExactly("G-003", "G-004");
        at("2026-09-20T18:30:00Z");
        assertThat(issue(s.service())).as("Monday 21 September 00:30 in Dhaka: a new week").isEqualTo("G-001");
    }

    // ---- FR-CFG-019 / UAT U10: resets ---------------------------------------------------------------------------

    @Test
    void theSequenceRestartsAtTheSiteLocalResetTimeAndPriorDaysStayIntact() throws Exception {
        Setup s = setup("H");
        String admin = token(Role.ORG_ADMIN, s.site());
        assertThat(status(putRule(admin, "services", s.service(), "{\"prefix_source\":\"service\",\"reset_time\":\"04:00\"}"))).isEqualTo(200);

        at("2026-09-19T17:00:00Z"); // 23:00 Dhaka on the 19th
        assertThat(issue(s.service(), 3)).containsExactly("H-001", "H-002", "H-003");
        at("2026-09-19T19:00:00Z"); // 01:00 on the 20th: past midnight, before the 04:00 reset
        assertThat(issue(s.service())).as("midnight is not this rule's boundary").isEqualTo("H-004");
        at("2026-09-19T22:00:00Z"); // 04:00 on the 20th
        assertThat(issue(s.service())).as("the sequence restarts at the reset time").isEqualTo("H-001");
        assertThat(issue(s.service())).isEqualTo("H-002");

        assertThat(jdbc.queryForList("SELECT token_number FROM ticket WHERE service_id = ? AND reset_key = '2026-09-19' ORDER BY sequence_no", String.class, s.service()))
                .as("the previous day's tickets are untouched").containsExactly("H-001", "H-002", "H-003", "H-004");
        assertThat(jdbc.queryForList("SELECT token_number FROM ticket WHERE service_id = ? AND reset_key = '2026-09-20' ORDER BY sequence_no", String.class, s.service()))
                .containsExactly("H-001", "H-002");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT (reset_key, token_number)) FROM ticket WHERE service_id = ?", Integer.class, s.service())).isEqualTo(6);
        assertThat(ledger(s.site(), "H", "2026-09-20").get("triggered_by")).as("nothing ran the job, so the first ticket opened the period").isEqualTo("issuance");
    }

    @Test
    void aResetTheBackendMissedIsReplayedWhenTheJobRunsAgain() {
        Setup s = setup("J");
        at("2026-09-19T10:00:00Z");
        resets.runScheduled();
        assertThat(ledger(s.site(), "J", "2026-09-19").get("triggered_by")).as("the first start of a scope").isEqualTo("scheduled");
        assertThat(issue(s.service(), 3)).containsExactly("J-001", "J-002", "J-003");

        // The backend is down for three days, through three resets; nobody is issued a ticket either.
        at("2026-09-22T10:00:00Z");
        assertThat(resets.runScheduled()).isPresent();

        assertThat(ledger(s.site(), "J", "2026-09-22").get("triggered_by")).isEqualTo("replayed");
        assertThat(count("sequence_block", "scope_key = ? AND reset_key = '2026-09-22'", scopeKey(s.site(), "J"))).as("the new period's sequence is open at its start").isEqualTo(1);
        assertThat(issue(s.service())).as("the sequence restarted").isEqualTo("J-001");
        assertThat(count("ticket", "service_id = ? AND reset_key = '2026-09-19'", s.service())).as("the earlier day is intact").isEqualTo(3);

        // A second run, on this node or another, opens nothing more for the scope.
        resets.runScheduled();
        assertThat(count("numbering_reset", "scope_key = ?", scopeKey(s.site(), "J"))).isEqualTo(2);
        assertThat(count("audit_log", "action = 'numbering.reset' AND after ->> 'prefix' = 'J' AND after ->> 'site_id' = ?", s.site().toString())).isEqualTo(2);

        // The next reset is met on time: within minutes of the site-local reset time.
        at("2026-09-22T18:01:00Z"); // 00:01 on the 23rd in Dhaka
        resets.runScheduled();
        assertThat(ledger(s.site(), "J", "2026-09-23").get("triggered_by")).isEqualTo("scheduled");
        assertThat(sequences.peek(s.site(), "J", "2026-09-23")).hasValue(1);
    }

    @Test
    void weeklyMonthlyAndNeverBoundariesRestartOrContinueTheSequence() throws Exception {
        Setup weekly = setup("K");
        Setup monthly = setup("L");
        Setup never = setup("M");
        String admin = token(Role.ORG_ADMIN, weekly.site(), monthly.site(), never.site());
        putRule(admin, "services", weekly.service(), "{\"prefix_source\":\"service\",\"reset_boundary\":\"weekly\"}");
        putRule(admin, "services", monthly.service(), "{\"prefix_source\":\"service\",\"reset_boundary\":\"monthly\"}");
        putRule(admin, "services", never.service(), "{\"prefix_source\":\"service\",\"reset_boundary\":\"never\",\"sequence_start\":10}");

        at("2026-09-14T05:00:00Z"); // Monday
        List<String> first = List.of(issue(weekly.service()), issue(monthly.service()), issue(never.service()));
        at("2026-09-20T05:00:00Z"); // Sunday: same week
        List<String> sameWeek = List.of(issue(weekly.service()), issue(monthly.service()), issue(never.service()));
        at("2026-09-21T05:00:00Z"); // Monday: next week
        List<String> nextWeek = List.of(issue(weekly.service()), issue(monthly.service()), issue(never.service()));
        at("2026-10-01T05:00:00Z"); // next month
        List<String> nextMonth = List.of(issue(weekly.service()), issue(monthly.service()), issue(never.service()));

        assertThat(first).containsExactly("K-001", "L-001", "M-010");
        assertThat(sameWeek).containsExactly("K-002", "L-002", "M-011");
        assertThat(nextWeek).as("only the weekly rule restarts on Monday").containsExactly("K-001", "L-003", "M-012");
        assertThat(nextMonth).as("1 October is in the week of Monday 28 September, a new one for the weekly rule; the monthly rule restarts on the first; never does not")
                .containsExactly("K-001", "L-001", "M-013");
        assertThat(jdbc.queryForList("SELECT DISTINCT reset_key FROM ticket WHERE service_id = ? ORDER BY reset_key", String.class, weekly.service())).containsExactly("2026-W38", "2026-W39", "2026-W40");
        assertThat(jdbc.queryForList("SELECT DISTINCT reset_key FROM ticket WHERE service_id = ? ORDER BY reset_key", String.class, monthly.service())).containsExactly("2026-09", "2026-10");
        assertThat(jdbc.queryForList("SELECT DISTINCT reset_key FROM ticket WHERE service_id = ?", String.class, never.service())).containsExactly("never");
    }

    @Test
    void scheduledResetsRunOnceClusterWideUnderADatabaseLock() throws Exception {
        Setup s = setup("N");
        String key = scopeKey(s.site(), "N");

        // Another node holds the cluster-wide lock: this node skips the run and opens nothing.
        String lockKey = "('x' || substr(md5('" + NumberingResets.LOCK + "'), 1, 16))::bit(64)::bigint";
        try (Connection otherNode = dataSource.getConnection()) {
            otherNode.createStatement().execute("SELECT pg_advisory_lock(" + lockKey + ")");
            try {
                assertThat(resets.runScheduled()).as("the lock is taken").isEmpty();
                assertThat(count("numbering_reset", "scope_key = ?", key)).isZero();
            } finally {
                otherNode.createStatement().execute("SELECT pg_advisory_unlock(" + lockKey + ")");
            }
        }
        assertThat(resets.runScheduled()).as("free again").isPresent();
        assertThat(count("numbering_reset", "scope_key = ?", key)).isEqualTo(1);

        // Every node fires at the same moment: whoever runs, the period is opened and audited exactly once.
        Setup t = setup("P");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Optional<Integer>>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) tasks.add(resets::runScheduled);
            for (Future<Optional<Integer>> done : pool.invokeAll(tasks)) done.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("numbering_reset", "scope_key = ?", scopeKey(t.site(), "P"))).isEqualTo(1);
        assertThat(count("audit_log", "action = 'numbering.reset' AND after ->> 'prefix' = 'P' AND after ->> 'site_id' = ?", t.site().toString())).isEqualTo(1);
    }

    @Test
    void theScheduleTriggersTheResetOnEveryNodeAndTheLockDecidesWhoWorks() {
        Setup s = setup("Q");

        scheduler.tick();

        assertThat(count("numbering_reset", "scope_key = ?", scopeKey(s.site(), "Q"))).isEqualTo(1);
    }

    // ---- FR-QUE-201: sequence blocks ---------------------------------------------------------------------------

    @Test
    void theNextSequenceBlockIsRequestedWhenEightyPercentOfTheCurrentOneIsConsumed() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb)",
                site, "B-" + site.toString().substring(0, 8));
        String scope = scopeKey(site, "R");
        List<Long> drawn = new ArrayList<>();

        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 79; i++) drawn.add(sequences.next(site, "R", "2026-09-19"));
        });
        assertThat(count("sequence_block", "scope_key = ?", scope)).as("79% consumed: no request yet").isEqualTo(1);

        transactions.executeWithoutResult(status -> drawn.add(sequences.next(site, "R", "2026-09-19")));
        assertThat(count("sequence_block", "scope_key = ?", scope)).as("80% consumed: the next block is requested").isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT block_start, block_end, next_value FROM sequence_block WHERE scope_key = ? ORDER BY block_start DESC LIMIT 1", scope))
                .containsEntry("block_start", 101L).containsEntry("block_end", 200L).containsEntry("next_value", 101L);

        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 99; i++) drawn.add(sequences.next(site, "R", "2026-09-19"));
        });
        assertThat(drawn).as("continuous across the block boundary").hasSize(179).isSorted().doesNotHaveDuplicates().last().isEqualTo(179L);
        assertThat(count("sequence_block", "scope_key = ?", scope)).as("the second block is at 79%").isEqualTo(2);

        transactions.executeWithoutResult(status -> drawn.add(sequences.next(site, "R", "2026-09-19")));
        assertThat(drawn.getLast()).isEqualTo(180L);
        assertThat(count("sequence_block", "scope_key = ? AND block_start = 201 AND block_end = 300", scope)).as("and requests the third").isEqualTo(1);
        assertThat(count("sequence_block", "scope_key = ?", scope)).isEqualTo(3);
    }

    // ---- preview ------------------------------------------------------------------------------------------------

    @Test
    void thePreviewShowsTheNextNumberWithoutUsingItUp() throws Exception {
        Setup s = setup("S");
        UUID second = newService(s.group(), "T");
        String admin = token(Role.ORG_ADMIN, s.site());
        String preview = "/api/v1/services/" + s.service() + "/numbering-preview";

        MvcResult first = call(get(preview), admin, null);
        MvcResult again = call(get(preview), admin, null);

        assertThat((String) field(first, "$.items[0].token_number")).isEqualTo("S-001");
        assertThat(body(again)).as("looking does not use a number up").isEqualTo(body(first));
        assertThat((String) field(first, "$.items[0].rule_source")).isEqualTo("default");
        assertThat((String) field(first, "$.items[0].reset_key")).isEqualTo("2026-09-19");
        assertThat((String) field(first, "$.items[0].next_reset_at")).isEqualTo("2026-09-19T18:00:00Z");
        assertThat(count("sequence_block", "scope_key = ?", scopeKey(s.site(), "S"))).isZero();

        assertThat(issue(s.service())).isEqualTo("S-001");
        assertThat((String) field(call(get(preview), admin, null), "$.items[0].token_number")).isEqualTo("S-002");

        putRule(admin, "service-groups", s.group(), "{\"prefix_source\":\"service_group\",\"padding\":2,\"sequence_start\":50,\"separator\":\".\"}");
        MvcResult group = call(get("/api/v1/service-groups/" + s.group() + "/numbering-preview"), admin, null);
        assertThat((List<String>) field(group, "$.items[*].token_number")).as("one line per active service of the group, both on the group's prefix").containsExactly("GS.50", "GS.50");
        assertThat((List<String>) field(group, "$.items[*].rule_source")).containsExactly("service_group", "service_group");

        putRule(admin, "services", second, "{\"prefix_source\":\"service\",\"reset_boundary\":\"never\"}");
        MvcResult both = call(get("/api/v1/service-groups/" + s.group() + "/numbering-preview"), admin, null);
        assertThat((List<String>) field(both, "$.items[*].token_number")).containsExactlyInAnyOrder("GS.50", "T-001");
        MvcResult never = call(get("/api/v1/services/" + second + "/numbering-preview"), admin, null);
        assertThat((String) field(never, "$.items[0].rule_source")).isEqualTo("service");
        assertThat((Object) field(never, "$.items[0].next_reset_at")).as("a rule that never resets has no next reset").isNull();

        at("2026-09-19T18:00:00Z"); // the daily reset passes
        String tomorrow = token(Role.ORG_ADMIN, s.site());
        MvcResult next = call(get("/api/v1/service-groups/" + s.group() + "/numbering-preview"), tomorrow, null);
        assertThat((String) field(next, "$.items[0].reset_key")).as("the preview follows the reset").isEqualTo("2026-09-20");
        assertThat((String) field(next, "$.items[0].next_reset_at")).isEqualTo("2026-09-20T18:00:00Z");
        assertThat(status(call(get("/api/v1/service-groups/" + UUID.randomUUID() + "/numbering-preview"), tomorrow, null))).isEqualTo(404);
    }
}
