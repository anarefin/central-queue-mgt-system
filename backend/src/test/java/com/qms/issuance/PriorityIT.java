package com.qms.issuance;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Ticket 09 against real PostgreSQL with a clock the test moves: an admin defines Priority classes and picks an
 * ordering strategy per Service group, Reception gives a ticket a class at issue, the queue is ordered by the engine
 * and the dry-run shows every term (FR-QUE-001, FR-QUE-010, FR-QUE-020..023, ADR-0003, ADR-0004, UAT U6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, PriorityIT.Clocks.class})
class PriorityIT {

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
            return Files.createTempDirectory("qms-keys-priority");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        // The default class is shared by every test in the database; give it back its original settings.
        jdbc.update("UPDATE priority_class SET max_wait_minutes = NULL WHERE is_default");
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    /** A Dhaka site with one group and one reception service (prefix {@code p}). */
    private Setup setup(String prefix) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "P-" + site.toString().substring(0, 8));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, ?)", group, site, "G" + prefix);
        return new Setup(site, group, newService(group, prefix));
    }

    private UUID newService(UUID group, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, prefix);
        return id;
    }

    private UUID newClass(String name, int headstart, Integer maxWait, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, max_wait_minutes, token_prefix_override, created_at, updated_at) VALUES (?, ?::jsonb, ?, ?, ?, now(), now())",
                id, "{\"en\":\"" + name + "\"}", headstart, maxWait, prefix);
        return id;
    }

    private UUID defaultClass() {
        return jdbc.queryForObject("SELECT id FROM priority_class WHERE is_default", UUID.class);
    }

    private void setNormalMaxWait(int minutes) {
        jdbc.update("UPDATE priority_class SET max_wait_minutes = ? WHERE is_default", minutes);
    }

    /** Issues a ticket as if {@code minutesAgo} minutes before BASE, then puts the clock back at BASE. */
    private String issueAgo(UUID service, int minutesAgo, UUID priorityClass) {
        clock.set(BASE.minus(Duration.ofMinutes(minutesAgo)));
        try {
            return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null, priorityClass)).tokenNumber();
        } finally {
            clock.set(BASE);
        }
    }

    /** A signed-in token. It is minted at real time whatever the test clock says, because tokens are validated against the system clock. */
    private String token(Role role, UUID... sites) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
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
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult reception(String token, UUID service, UUID priorityClass) throws Exception {
        String body = "{\"service_id\":\"" + service + "\",\"origin_channel\":\"reception\""
                + (priorityClass == null ? "" : ",\"priority_class_id\":\"" + priorityClass + "\"") + "}";
        return call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), token, body);
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

    private List<String> queueOrder(String token, UUID service) throws Exception {
        MvcResult snapshot = call(get("/api/v1/queues/" + service), token, null);
        assertThat(status(snapshot)).as(body(snapshot)).isEqualTo(200);
        return field(snapshot, "$.tickets[*].token_number");
    }

    private MvcResult dryRun(String token, UUID service) throws Exception {
        return call(get("/api/v1/queues/" + service + "/dry-run"), token, null);
    }

    private static String classBody(String name, int headstart, Integer maxWait, String prefix) {
        return "{\"name_i18n\":{\"en\":\"" + name + "\"},\"headstart_minutes\":" + headstart
                + (maxWait == null ? "" : ",\"max_wait_minutes\":" + maxWait)
                + (prefix == null ? "" : ",\"token_prefix_override\":\"" + prefix + "\"") + "}";
    }

    // ---- FR-QUE-010: priority classes --------------------------------------------------------------------------

    @Test
    void anAdminDefinesPriorityClassesWithHeadStartMaximumWaitAndPrefixOverrideBesideTheDefaultNormalClass() throws Exception {
        Setup s = setup("A");
        String admin = token(Role.ORG_ADMIN, s.site());

        MvcResult created = call(post("/api/v1/priority-classes"), admin,
                "{\"name_i18n\":{\"en\":\"Senior citizen\",\"bn\":\"বয়স্ক নাগরিক\"},\"headstart_minutes\":20,\"max_wait_minutes\":45,\"token_prefix_override\":\"SC\"}");

        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((Integer) field(created, "$.headstart_minutes")).isEqualTo(20);
        assertThat((Integer) field(created, "$.max_wait_minutes")).isEqualTo(45);
        assertThat((String) field(created, "$.token_prefix_override")).isEqualTo("SC");
        assertThat((String) field(created, "$.name_i18n.bn")).isEqualTo("বয়স্ক নাগরিক");
        assertThat((Boolean) field(created, "$.is_default")).isFalse();
        assertThat((Boolean) field(created, "$.active")).isTrue();

        MvcResult listed = call(get("/api/v1/priority-classes"), admin, null);
        assertThat(status(listed)).isEqualTo(200);
        assertThat((List<Boolean>) field(listed, "$.items[*].is_default")).as("the default class comes first").first().isEqualTo(true);
        assertThat((Integer) field(listed, "$.items[0].headstart_minutes")).as("normal = 0").isZero();
        assertThat((String) field(listed, "$.items[0].name_i18n.en")).isEqualTo("Normal");
        assertThat((List<String>) field(listed, "$.items[*].name_i18n.en")).contains("Senior citizen");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM priority_class WHERE is_default", Integer.class)).isEqualTo(1);
    }

    @Test
    void aClassCanBeChangedAndSwitchedOffWithoutBreakingTicketsThatCarryIt() throws Exception {
        Setup s = setup("B");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID emergency = newClass("Emergency", 60, null, null);
        String token = issueAgo(s.service(), 5, emergency);

        MvcResult changed = call(put("/api/v1/priority-classes/" + emergency), admin, classBody("Emergency", 90, 10, "EM"));
        assertThat(status(changed)).as(body(changed)).isEqualTo(200);
        assertThat((Integer) field(changed, "$.headstart_minutes")).isEqualTo(90);
        assertThat((Integer) field(changed, "$.max_wait_minutes")).isEqualTo(10);

        MvcResult off = call(post("/api/v1/priority-classes/" + emergency + "/deactivate"), admin, "{\"reason\":\"retired\"}");
        assertThat(status(off)).isEqualTo(200);
        assertThat((Boolean) field(off, "$.active")).isFalse();
        MvcResult ticket = call(get("/api/v1/queues/" + s.service()), admin, null);
        assertThat((List<String>) field(ticket, "$.tickets[*].token_number")).as("the ticket still waits under the switched-off class").containsExactly(token);
        assertThat((String) field(ticket, "$.tickets[0].priority_class.name_i18n.en")).isEqualTo("Emergency");

        MvcResult on = call(post("/api/v1/priority-classes/" + emergency + "/activate"), admin, null);
        assertThat((Boolean) field(on, "$.active")).isTrue();
    }

    @Test
    void theDefaultClassKeepsAHeadStartOfZeroCannotBeSwitchedOffButCanTakeAMaximumWait() throws Exception {
        Setup s = setup("C");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID normal = defaultClass();

        assertThat(status(call(put("/api/v1/priority-classes/" + normal), admin, classBody("Normal", 5, null, null)))).isEqualTo(400);
        assertThat(status(call(put("/api/v1/priority-classes/" + normal), admin, classBody("Normal", 0, null, "N")))).isEqualTo(400);
        MvcResult off = call(post("/api/v1/priority-classes/" + normal + "/deactivate"), admin, null);
        assertThat(status(off)).isEqualTo(409);
        assertThat((String) field(off, "$.error.details.reason")).isEqualTo("default_priority_class");

        MvcResult capped = call(put("/api/v1/priority-classes/" + normal), admin, "{\"name_i18n\":{\"en\":\"Normal\",\"bn\":\"সাধারণ\"},\"headstart_minutes\":0,\"max_wait_minutes\":60}");
        assertThat(status(capped)).as(body(capped)).isEqualTo(200);
        assertThat((Integer) field(capped, "$.max_wait_minutes")).isEqualTo(60);
    }

    @Test
    void invalidClassesAreRefusedNamingTheFieldAndStoreNothing() throws Exception {
        Setup s = setup("D");
        String admin = token(Role.ORG_ADMIN, s.site());
        int before = jdbc.queryForObject("SELECT count(*) FROM priority_class", Integer.class);
        Map<String, String> bad = Map.of(
                "name_i18n", "{\"headstart_minutes\":5}",
                "unknown language", "{\"name_i18n\":{\"xx\":\"Nope\"},\"headstart_minutes\":5}",
                "default language missing", "{\"name_i18n\":{\"bn\":\"শুধু বাংলা\"},\"headstart_minutes\":5}",
                "negative head start", classBody("X", -1, null, null),
                "head start too big", classBody("X", 1441, null, null),
                "zero max wait", classBody("X", 5, 0, null),
                "prefix punctuation", classBody("X", 5, null, "A-B"),
                "prefix too long", classBody("X", 5, null, "ABCDEFGHI"));
        bad.forEach((why, json) -> {
            try {
                MvcResult refused = call(post("/api/v1/priority-classes"), admin, json);
                assertThat(status(refused)).as(why).isEqualTo(400);
                assertThat((String) field(refused, "$.error.code")).as(why).isEqualTo("validation_failed");
            } catch (Exception e) {
                throw new AssertionError(why, e);
            }
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM priority_class", Integer.class)).isEqualTo(before);
        assertThat(status(call(put("/api/v1/priority-classes/" + UUID.randomUUID()), admin, classBody("X", 5, null, null)))).as("unknown class").isEqualTo(404);
    }

    @Test
    void everyPriorityClassActionIsCheckedOnTheServer() throws Exception {
        Setup s = setup("E");
        UUID some = newClass("Some", 5, null, null);
        for (Role role : List.of(Role.TEAM_ADMIN, Role.AGENT, Role.RECEPTION_OPERATOR)) {
            String token = token(role, s.site());
            assertThat(status(call(post("/api/v1/priority-classes"), token, classBody("Nope", 1, null, null)))).as(role + " create").isEqualTo(403);
            assertThat(status(call(put("/api/v1/priority-classes/" + some), token, classBody("Nope", 1, null, null)))).as(role + " replace").isEqualTo(403);
            assertThat(status(call(post("/api/v1/priority-classes/" + some + "/deactivate"), token, null))).as(role + " deactivate").isEqualTo(403);
            assertThat(status(call(post("/api/v1/priority-classes/" + some + "/activate"), token, null))).as(role + " activate").isEqualTo(403);
        }
        assertThat(status(call(get("/api/v1/priority-classes"), token(Role.RECEPTION_OPERATOR, s.site()), null))).as("Reception reads the classes to offer them").isEqualTo(200);
        assertThat(status(call(get("/api/v1/priority-classes"), token(Role.AGENT, s.site()), null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/priority-classes"), null, null))).as("unauthenticated").isEqualTo(401);
        assertThat(status(call(post("/api/v1/priority-classes"), null, classBody("Nope", 1, null, null)))).isEqualTo(401);
        for (Role role : List.of(Role.SYSTEM_ADMIN, Role.ORG_ADMIN)) {
            assertThat(status(call(post("/api/v1/priority-classes"), token(role, s.site()), classBody("By " + role.wire(), 1, null, null)))).as(role.wire()).isEqualTo(201);
        }
    }

    @Test
    void classChangesAreAuditedWithBeforeAndAfterValues() throws Exception {
        Setup s = setup("F");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID id = UUID.fromString(field(call(post("/api/v1/priority-classes"), admin, classBody("Audited", 10, null, null)), "$.id"));
        call(put("/api/v1/priority-classes/" + id), admin, classBody("Audited", 25, 40, null));
        call(put("/api/v1/priority-classes/" + id), admin, classBody("Audited", 25, 40, null));
        call(post("/api/v1/priority-classes/" + id + "/deactivate"), admin, "{\"reason\":\"not needed\"}");
        call(post("/api/v1/priority-classes/" + id + "/activate"), admin, null);

        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log WHERE entity = 'priority_class' AND entity_id = ?", String.class, id);
        assertThat(actions).as("an identical repeat writes nothing").containsExactlyInAnyOrder(
                "priority_class.created", "priority_class.updated", "priority_class.deactivated", "priority_class.activated");
        Map<String, Object> updated = jdbc.queryForMap("SELECT before::text AS before, after::text AS after, actor_role FROM audit_log WHERE action = 'priority_class.updated' AND entity_id = ?", id);
        assertThat((String) updated.get("before")).contains("\"headstart_minutes\": 10");
        assertThat((String) updated.get("after")).contains("\"headstart_minutes\": 25").contains("\"max_wait_minutes\": 40");
        assertThat(updated.get("actor_role")).isEqualTo("org_admin");
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action = 'priority_class.deactivated' AND entity_id = ?", String.class, id)).isEqualTo("not needed");
    }

    // ---- FR-QUE-021: a strategy per Service group -------------------------------------------------------------

    @Test
    void aServiceGroupOrdersByWeightedWaitUntilAnAdminPicksAnotherStrategy() throws Exception {
        Setup s = setup("G");
        String admin = token(Role.ORG_ADMIN, s.site());

        MvcResult initial = call(get("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, null);
        assertThat((String) field(initial, "$.strategy")).isEqualTo("weighted_wait");
        assertThat((Boolean) field(initial, "$.is_default")).isTrue();
        assertThat((List<String>) field(initial, "$.available")).containsExactly("weighted_wait", "strict_priority", "fifo");

        MvcResult set = call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"strict_priority\"}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
        assertThat((String) field(set, "$.strategy")).isEqualTo("strict_priority");
        assertThat((Boolean) field(set, "$.is_default")).isFalse();
        assertThat((String) field(call(get("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, null), "$.strategy")).isEqualTo("strict_priority");

        for (String bad : List.of("{\"strategy\":\"lifo\"}", "{}", "")) {
            MvcResult refused = call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, bad.isEmpty() ? null : bad);
            assertThat(status(refused)).as(bad).isEqualTo(400);
        }
        assertThat((String) field(call(get("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, null), "$.strategy")).as("a refused change stores nothing").isEqualTo("strict_priority");
        assertThat(status(call(put("/api/v1/service-groups/" + UUID.randomUUID() + "/routing-strategy"), admin, "{\"strategy\":\"fifo\"}"))).isEqualTo(404);

        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"strict_priority\"}");
        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"fifo\"}");
        Map<String, Object> audited = jdbc.queryForMap(
                "SELECT before::text AS before, after::text AS after FROM audit_log WHERE action = 'routing_strategy.updated' AND entity_id = ? AND after::text LIKE '%fifo%'", s.group());
        assertThat((String) audited.get("before")).contains("strict_priority");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'routing_strategy.updated' AND entity_id = ?", Integer.class, s.group()))
                .as("weighted_wait to strict_priority, strict_priority to fifo; the repeat wrote nothing").isEqualTo(2);
    }

    @Test
    void strategyChangesAreCheckedOnTheServerAndScopedToTheCallersSites() throws Exception {
        Setup s = setup("H");
        Setup other = setup("I");
        String url = "/api/v1/service-groups/" + s.group() + "/routing-strategy";
        for (Role role : List.of(Role.TEAM_ADMIN, Role.AGENT, Role.RECEPTION_OPERATOR)) {
            String token = token(role, s.site());
            assertThat(status(call(put(url), token, "{\"strategy\":\"fifo\"}"))).as(role + " set").isEqualTo(403);
            assertThat(status(call(get(url), token, null))).as(role + " read").isEqualTo(403);
        }
        assertThat(status(call(put(url), null, "{\"strategy\":\"fifo\"}"))).isEqualTo(401);
        String elsewhere = token(Role.ORG_ADMIN, other.site());
        assertThat(status(call(put(url), elsewhere, "{\"strategy\":\"fifo\"}"))).as("another site's Org Admin").isEqualTo(403);
        assertThat(status(call(get(url), elsewhere, null))).isEqualTo(403);
        assertThat(status(call(put(url), token(Role.SYSTEM_ADMIN, s.site()), "{\"strategy\":\"fifo\"}"))).isEqualTo(200);
    }

    // ---- FR-QUE-020, FR-QUE-021: ordering under each strategy -------------------------------------------------

    @Test
    void eachStrategyOrdersTheSameWaitingTicketsItsOwnWay() throws Exception {
        Setup s = setup("J");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID low = newClass("Low", 10, null, null);
        UUID high = newClass("High", 30, null, null);
        String l = issueAgo(s.service(), 30, low);       // score 30 + 10 = 40
        String n = issueAgo(s.service(), 20, null);      // score 20
        String q = issueAgo(s.service(), 5, high);       // score 5 + 30 = 35
        String p = issueAgo(s.service(), 1, high);       // score 1 + 30 = 31

        assertThat(queueOrder(admin, s.service())).as("weighted_wait is the default").containsExactly(l, q, p, n);

        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"strict_priority\"}");
        assertThat(queueOrder(admin, s.service())).as("class first, then first come first served").containsExactly(q, p, l, n);

        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"fifo\"}");
        assertThat(queueOrder(admin, s.service())).as("creation order only").containsExactly(l, n, q, p);
    }

    @Test
    void aPriorityVisitorArrivingIntoALongNormalQueueIsServedAheadOfThoseWhoWaitedLessThanTheHeadStart() throws Exception {
        // UAT U6.
        Setup s = setup("K");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID senior = newClass("Senior citizen", 20, null, null);
        String n1 = issueAgo(s.service(), 25, null);
        String n2 = issueAgo(s.service(), 15, null);
        String n3 = issueAgo(s.service(), 5, null);

        MvcResult issued = reception(reception, s.service(), senior);

        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        String p = field(issued, "$.token_number");
        assertThat((Integer) field(issued, "$.position")).as("ahead of the two that waited less than 20 minutes").isEqualTo(2);
        assertThat((String) field(issued, "$.priority_class.name_i18n.en")).isEqualTo("Senior citizen");
        assertThat(queueOrder(admin, s.service())).containsExactly(n1, p, n2, n3);
    }

    @Test
    void noTicketPastItsClassesMaximumWaitIsLeftBehindAndTheDashboardCanTellWhichAreEscalated() throws Exception {
        // UAT U6: "no normal ticket breaches its max wait".
        Setup s = setup("L");
        String admin = token(Role.ORG_ADMIN, s.site());
        setNormalMaxWait(60);
        UUID senior = newClass("Senior citizen", 20, null, null);
        String n1 = issueAgo(s.service(), 25, null);
        String n2 = issueAgo(s.service(), 15, null);
        String n3 = issueAgo(s.service(), 5, null);
        String p = issueAgo(s.service(), 0, senior);
        assertThat(queueOrder(admin, s.service())).containsExactly(n1, p, n2, n3);

        clock.set(BASE.plus(Duration.ofMinutes(40)));   // n1 65, n2 55, n3 45, p 40 + 20 = 60
        assertThat(queueOrder(admin, s.service())).as("n1 is past 60 minutes and goes first").containsExactly(n1, p, n2, n3);
        assertThat((List<Boolean>) field(call(get("/api/v1/queues/" + s.service()), admin, null), "$.tickets[*].escalated")).containsExactly(true, false, false, false);

        clock.set(BASE.plus(Duration.ofMinutes(50)));   // n1 75, n2 65: both past 60; p 50 + 20 = 70; n3 55
        assertThat(queueOrder(admin, s.service())).as("both overdue normal tickets now precede the priority visitor").containsExactly(n1, n2, p, n3);
        assertThat((List<Boolean>) field(call(get("/api/v1/queues/" + s.service()), admin, null), "$.tickets[*].escalated")).containsExactly(true, true, false, false);
    }

    @Test
    void escalationOverridesANegativeScoreAdjustmentAndTheDryRunSaysSo() throws Exception {
        Setup s = setup("M");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID urgent = newClass("Urgent", 0, 30, null);
        String moved = issueAgo(s.service(), 40, urgent);
        String longest = issueAgo(s.service(), 100, null);
        jdbc.update("UPDATE ticket SET score_adjustment_minutes = -500 WHERE token_number = ? AND service_id = ?", moved, s.service());

        assertThat(queueOrder(admin, s.service())).as("the hard cap on waiting beats the positional move").containsExactly(moved, longest);
        MvcResult dry = dryRun(admin, s.service());
        assertThat((Boolean) field(dry, "$.tickets[0].terms.adjustment_overridden")).isTrue();
        assertThat((Double) field(dry, "$.tickets[0].terms.score_adjustment_minutes")).isZero();
        assertThat((Boolean) field(dry, "$.tickets[0].escalated")).isTrue();

        jdbc.update("UPDATE ticket SET score_adjustment_minutes = -500 WHERE token_number = ? AND service_id = ?", longest, s.service());
        assertThat(queueOrder(admin, s.service())).as("without a maximum wait the adjustment holds").containsExactly(moved, longest);
        UUID fresh = newClass("Fresh", 0, 30, null);
        String notYet = issueAgo(s.service(), 10, fresh);
        jdbc.update("UPDATE ticket SET score_adjustment_minutes = -500 WHERE token_number = ? AND service_id = ?", notYet, s.service());
        assertThat(queueOrder(admin, s.service())).as("a ticket that is not escalated stays where its adjustment put it").containsExactly(moved, longest, notYet);
    }

    // ---- FR-QUE-023: the dry-run -------------------------------------------------------------------------------

    @Test
    void theDryRunReturnsTheComputedOrderWithEveryTermOfEveryScore() throws Exception {
        Setup s = setup("N");
        String admin = token(Role.ORG_ADMIN, s.site());
        setNormalMaxWait(60);
        UUID senior = newClass("Senior citizen", 20, 45, null);
        String old = issueAgo(s.service(), 25, null);
        String priority = issueAgo(s.service(), 5, senior);
        jdbc.update("UPDATE ticket SET score_adjustment_minutes = -3 WHERE token_number = ? AND service_id = ?", priority, s.service());
        jdbc.update("INSERT INTO routing_strategy (service_group_id, strategy, updated_at) VALUES (?, 'weighted_wait', now())", s.group());

        MvcResult dry = dryRun(admin, s.service());

        assertThat(status(dry)).as(body(dry)).isEqualTo(200);
        assertThat((String) field(dry, "$.strategy")).isEqualTo("weighted_wait");
        assertThat((Integer) field(dry, "$.waiting_count")).isEqualTo(2);
        assertThat((String) field(dry, "$.computed_at")).isNotBlank();
        assertThat((String) field(dry, "$.service.name_i18n.en")).isEqualTo("Consultation");
        assertThat((List<String>) field(dry, "$.tickets[*].token_number")).as("25 vs 5 + 20 - 3 = 22").containsExactly(old, priority);
        assertThat((List<Integer>) field(dry, "$.tickets[*].position")).containsExactly(1, 2);
        assertThat((Double) field(dry, "$.tickets[0].terms.effective_wait_minutes")).isEqualTo(25.0);
        assertThat((Double) field(dry, "$.tickets[0].terms.headstart_minutes")).isZero();
        assertThat((Double) field(dry, "$.tickets[0].terms.appointment_bonus")).isZero();
        assertThat((Double) field(dry, "$.tickets[0].terms.escalation_bonus")).isZero();
        assertThat((Double) field(dry, "$.tickets[0].terms.score_adjustment_minutes")).isZero();
        assertThat((Double) field(dry, "$.tickets[0].score")).isEqualTo(25.0);
        assertThat((Integer) field(dry, "$.tickets[0].max_wait_minutes")).isEqualTo(60);
        assertThat((Double) field(dry, "$.tickets[1].terms.effective_wait_minutes")).isEqualTo(5.0);
        assertThat((Double) field(dry, "$.tickets[1].terms.headstart_minutes")).isEqualTo(20.0);
        assertThat((Double) field(dry, "$.tickets[1].terms.score_adjustment_minutes")).isEqualTo(-3.0);
        assertThat((Double) field(dry, "$.tickets[1].score")).isEqualTo(22.0);
        assertThat((String) field(dry, "$.tickets[1].priority_class.name_i18n.en")).isEqualTo("Senior citizen");
        assertThat((Integer) field(dry, "$.tickets[1].max_wait_minutes")).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ? AND state = 'waiting'", Integer.class, s.service())).as("nothing was served or changed").isEqualTo(2);
    }

    @Test
    void theDryRunCanTryAnotherStrategyWithoutChangingTheGroup() throws Exception {
        Setup s = setup("O");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID high = newClass("High", 30, null, null);
        String n = issueAgo(s.service(), 20, null);
        String p = issueAgo(s.service(), 1, high);

        MvcResult weighted = dryRun(admin, s.service());
        MvcResult fifo = call(get("/api/v1/queues/" + s.service() + "/dry-run").param("strategy", "fifo"), admin, null);

        assertThat((List<String>) field(weighted, "$.tickets[*].token_number")).containsExactly(p, n);
        assertThat((String) field(fifo, "$.strategy")).isEqualTo("fifo");
        assertThat((List<String>) field(fifo, "$.tickets[*].token_number")).containsExactly(n, p);
        assertThat((String) field(call(get("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, null), "$.strategy")).isEqualTo("weighted_wait");
        assertThat(status(call(get("/api/v1/queues/" + s.service() + "/dry-run").param("strategy", "lifo"), admin, null))).isEqualTo(400);
    }

    @Test
    void theDryRunIsForAdminsOfTheQueuesSite() throws Exception {
        Setup s = setup("P");
        Setup other = setup("Q");
        for (Role role : List.of(Role.TEAM_ADMIN, Role.AGENT, Role.RECEPTION_OPERATOR)) {
            assertThat(status(dryRun(token(role, s.site()), s.service()))).as(role.wire()).isEqualTo(403);
        }
        assertThat(status(dryRun(null, s.service()))).isEqualTo(401);
        assertThat(status(dryRun(token(Role.ORG_ADMIN, other.site()), s.service()))).as("another site's admin").isEqualTo(403);
        assertThat(status(dryRun(token(Role.ORG_ADMIN, s.site()), UUID.randomUUID()))).isEqualTo(404);
        assertThat(status(dryRun(token(Role.SYSTEM_ADMIN, s.site()), s.service()))).isEqualTo(200);
    }

    @Test
    void aQueueOfFiveHundredTicketsIsOrderedAndDryRunInOneRequest() throws Exception {
        Setup s = setup("R");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID senior = newClass("Senior citizen", 20, null, null);
        jdbc.update(
                "INSERT INTO visit (id, site_id, started_at) SELECT gen_random_uuid(), ?, now() FROM generate_series(1, 500)", s.site());
        List<UUID> visits = jdbc.queryForList("SELECT id FROM visit WHERE site_id = ?", UUID.class, s.site());
        for (int i = 0; i < 500; i++) {
            Instant queued = BASE.minus(Duration.ofSeconds(i * 7L));
            jdbc.update(
                    "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, visit_id, origin_channel, state, issued_at, queued_at, secret_hash, priority_class_id)"
                            + " VALUES (?, ?, ?, 'bulk', ?, ?, ?, ?, 'reception', 'waiting', ?, ?, 'x', ?)",
                    UUID.randomUUID(), "R-" + i, i, s.service(), s.group(), s.site(), visits.get(i), java.sql.Timestamp.from(queued), java.sql.Timestamp.from(queued), i % 10 == 0 ? senior : null);
        }

        MvcResult dry = dryRun(admin, s.service());

        assertThat(status(dry)).isEqualTo(200);
        assertThat((Integer) field(dry, "$.waiting_count")).isEqualTo(500);
        List<Double> scores = field(dry, "$.tickets[*].score");
        assertThat(scores).hasSize(500).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    // ---- FR-QUE-011: a class at issue --------------------------------------------------------------------------

    @Test
    void receptionGivesATicketAClassAtIssueAndTheDefaultClassWhenItChoosesNone() throws Exception {
        Setup s = setup("S");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID senior = newClass("Senior citizen", 20, null, null);

        MvcResult chosen = reception(reception, s.service(), senior);
        MvcResult none = reception(reception, s.service(), null);

        assertThat(status(chosen)).as(body(chosen)).isEqualTo(201);
        assertThat((String) field(chosen, "$.priority_class.id")).isEqualTo(senior.toString());
        assertThat((String) field(none, "$.priority_class.name_i18n.en")).isEqualTo("Normal");
        UUID ticket = UUID.fromString(field(chosen, "$.id"));
        assertThat(jdbc.queryForObject("SELECT priority_class_id FROM ticket WHERE id = ?", UUID.class, ticket)).isEqualTo(senior);
        assertThat(jdbc.queryForObject("SELECT priority_class_id FROM ticket WHERE id = ?", UUID.class, UUID.fromString(field(none, "$.id")))).as("no choice stores no class").isNull();
        assertThat(jdbc.queryForObject("SELECT payload->>'priority_class_id' FROM ticket_event WHERE ticket_id = ?", String.class, ticket)).isEqualTo(senior.toString());
        assertThat(jdbc.queryForObject("SELECT after->>'priority_class_id' FROM audit_log WHERE action = 'ticket.issued' AND entity_id = ?", String.class, ticket)).isEqualTo(senior.toString());
        MvcResult read = call(get("/api/v1/tickets/" + ticket), reception, null);
        assertThat((String) field(read, "$.priority_class.name_i18n.en")).isEqualTo("Senior citizen");
    }

    @Test
    void aClassThatDoesNotExistOrIsSwitchedOffCannotBeGivenAtIssue() throws Exception {
        Setup s = setup("T");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID retired = newClass("Retired", 20, null, null);
        jdbc.update("UPDATE priority_class SET active = false WHERE id = ?", retired);

        MvcResult unknown = reception(reception, s.service(), UUID.randomUUID());
        MvcResult inactive = reception(reception, s.service(), retired);

        assertThat(status(unknown)).isEqualTo(400);
        assertThat((String) field(unknown, "$.error.details.fields[0].field")).isEqualTo("priority_class_id");
        assertThat(status(inactive)).isEqualTo(400);
        assertThat((String) field(inactive, "$.error.details.fields[0].code")).isEqualTo("inactive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ?", Integer.class, s.service())).as("nothing was issued").isZero();
    }

    @Test
    void retryingTheSameKeyWithADifferentClassIsNotASilentReplay() throws Exception {
        Setup s = setup("U");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID senior = newClass("Senior citizen", 20, null, null);
        String key = UUID.randomUUID().toString();
        String plain = "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\"}";
        String withClass = "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\",\"priority_class_id\":\"" + senior + "\"}";

        MvcResult first = call(post("/api/v1/tickets").header("Idempotency-Key", key), reception, plain);
        MvcResult second = call(post("/api/v1/tickets").header("Idempotency-Key", key), reception, withClass);

        assertThat(status(first)).isEqualTo(201);
        assertThat(status(second)).isEqualTo(409);
    }

    @Test
    void aNumberingRuleThatTakesItsPrefixFromThePriorityClassUsesTheClassOverride() throws Exception {
        Setup s = setup("V");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID vip = newClass("VIP", 40, null, "VIP");
        UUID plainClass = newClass("No override", 5, null, null);
        assertThat(status(call(put("/api/v1/services/" + s.service() + "/numbering-rule"), admin, "{\"prefix_source\":\"priority_class\"}"))).isEqualTo(200);

        assertThat(issueAgo(s.service(), 0, vip)).isEqualTo("VIP-001");
        assertThat(issueAgo(s.service(), 0, plainClass)).as("a class without an override leaves the service prefix").isEqualTo("V-001");
        assertThat(issueAgo(s.service(), 0, null)).isEqualTo("V-002");
        assertThat(issueAgo(s.service(), 0, vip)).isEqualTo("VIP-002");
    }

    // ---- FR-QUE-001: one logical queue per (Site, Service) ----------------------------------------------------

    @Test
    void thereIsOneLogicalQueuePerServiceAndTicketsNeverMoveTables() throws Exception {
        Setup a = setup("W");
        Setup b = setup("X");
        UUID second = newService(a.group(), "Y");
        String admin = token(Role.SYSTEM_ADMIN, a.site(), b.site());
        String a1 = issueAgo(a.service(), 3, null);
        String a2 = issueAgo(a.service(), 2, null);
        String second1 = issueAgo(second, 4, null);
        String b1 = issueAgo(b.service(), 9, null);

        assertThat(queueOrder(admin, a.service())).containsExactly(a1, a2);
        assertThat(queueOrder(admin, second)).containsExactly(second1);
        assertThat(queueOrder(admin, b.service())).containsExactly(b1);

        int rows = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id IN (?, ?, ?)", Integer.class, a.service(), second, b.service());
        jdbc.update("UPDATE ticket SET state = 'called' WHERE token_number = ? AND service_id = ?", a1, a.service());
        assertThat(queueOrder(admin, a.service())).as("leaving the queue is a change of state").containsExactly(a2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id IN (?, ?, ?)", Integer.class, a.service(), second, b.service())).as("the row is still in the same table").isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'queue%' OR table_name LIKE '%_queue'", Integer.class)).as("no per-queue table exists").isZero();
    }
}
