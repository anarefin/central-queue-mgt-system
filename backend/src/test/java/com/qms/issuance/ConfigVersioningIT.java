package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 55 against real PostgreSQL: Priority classes, routing strategies, numbering rules and business-hours weeks
 * are versioned with author and timestamp and revertible (FR-CFG-040), a change warns how many waiting Tickets it
 * touches without renumbering or reprioritising them retroactively (FR-CFG-041), {@code config.changed} reaches the
 * device fleet, and the whole configuration round-trips through a signed bundle (CFG-004).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, ConfigVersioningIT.Clocks.class, ConfigVersioningIT.Recording.class})
class ConfigVersioningIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final String BUNDLE_SECRET = "test-bundle-secret";
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

    /** Stands in for the realtime hub and remembers what was published, in order. */
    static class Recorder implements RealtimePublisher {
        record Published(String topic, String type) {}

        final List<Published> published = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(String topic, String type, Instant occurredAt, Map<String, Object> data) {
            published.add(new Published(topic, type));
        }

        @Override
        public void principalChanged(String subject) {}
    }

    @TestConfiguration
    static class Recording {
        @Bean
        @Primary
        Recorder recorder() {
            return new Recorder();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.config.bundle.secret", () -> BUNDLE_SECRET);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-config-versioning");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired Recorder recorder;
    @Autowired JsonMapper mapper;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        recorder.published.clear();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("UPDATE priority_class SET max_wait_minutes = NULL WHERE is_default");
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private Setup setup(String prefix) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "V-" + site.toString().substring(0, 8));
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

    private UUID newDevice(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id, kind, site_id, label) VALUES (?, 'kiosk', ?, 'Test kiosk')", id, site);
        return id;
    }

    private String issueWaiting(UUID service, UUID priorityClass) {
        return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null, priorityClass)).tokenNumber();
    }

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

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private String importBody(String payloadJson, String signature) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("payload_json", payloadJson);
        body.put("signature", signature);
        return mapper.writeValueAsString(body);
    }

    private static String classBody(String name, int headstart, Integer maxWait, String prefix) {
        return "{\"name_i18n\":{\"en\":\"" + name + "\"},\"headstart_minutes\":" + headstart
                + (maxWait == null ? "" : ",\"max_wait_minutes\":" + maxWait)
                + (prefix == null ? "" : ",\"token_prefix_override\":\"" + prefix + "\"") + "}";
    }

    // ---- FR-CFG-040: Priority class versioning and revert -------------------------------------------------------

    @Test
    void aPriorityClassChangeIsVersionedWithAuthorAndTimestampAndRevertibleToAnyPriorVersion() throws Exception {
        Setup s = setup("A");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID adminId = UUID.fromString((String) field(call(get("/api/v1/auth/me"), admin, null), "$.id"));
        UUID classId = UUID.fromString((String) field(call(post("/api/v1/priority-classes"), admin, classBody("Emergency", 30, null, null)), "$.id"));

        clock.set(BASE.plusSeconds(1));
        call(put("/api/v1/priority-classes/" + classId), admin, classBody("Emergency", 60, 20, "EM"));

        MvcResult history = call(get("/api/v1/priority-classes/" + classId + "/versions"), admin, null);
        assertThat(status(history)).as(body(history)).isEqualTo(200);
        List<Integer> headstarts = field(history, "$.items[*].payload.headstart_minutes");
        assertThat(headstarts).as("newest first").containsExactly(60, 30);
        assertThat((String) field(history, "$.items[0].changed_by")).isEqualTo(adminId.toString());
        assertThat((String) field(history, "$.items[0].changed_at")).isNotBlank();
        UUID firstVersionId = UUID.fromString((String) field(history, "$.items[1].id"));

        clock.set(BASE.plusSeconds(2));
        MvcResult reverted = call(post("/api/v1/priority-classes/" + classId + "/versions/" + firstVersionId + "/revert"), admin, null);
        assertThat(status(reverted)).as(body(reverted)).isEqualTo(200);
        assertThat((Integer) field(reverted, "$.headstart_minutes")).isEqualTo(30);
        assertThat((String) field(reverted, "$.token_prefix_override")).isNull();

        MvcResult historyAfterRevert = call(get("/api/v1/priority-classes/" + classId + "/versions"), admin, null);
        assertThat((List<Integer>) (List<?>) field(historyAfterRevert, "$.items[*].payload.headstart_minutes")).as("the revert is itself a new version").containsExactly(30, 60, 30);
    }

    @Test
    void aPriorityClassDeactivationWarnsHowManyWaitingTicketsCarryItAndDoesNotTouchThem() throws Exception {
        Setup s = setup("B");
        String admin = token(Role.ORG_ADMIN, s.site());
        UUID classId = UUID.fromString((String) field(call(post("/api/v1/priority-classes"), admin, classBody("Senior", 20, null, null)), "$.id"));
        String token = issueWaiting(s.service(), classId);

        MvcResult impact = call(get("/api/v1/priority-classes/" + classId + "/impact"), admin, null);
        assertThat(status(impact)).as(body(impact)).isEqualTo(200);
        assertThat((Integer) field(impact, "$.affected_waiting_tickets")).isEqualTo(1);

        call(post("/api/v1/priority-classes/" + classId + "/deactivate"), admin, "{\"reason\":\"retired\"}");

        assertThat(jdbc.queryForObject("SELECT priority_class_id FROM ticket WHERE token_number = ?", UUID.class, token))
                .as("no retroactive reprioritisation (FR-CFG-041)").isEqualTo(classId);
        MvcResult impactAfter = call(get("/api/v1/priority-classes/" + classId + "/impact"), admin, null);
        assertThat((Integer) field(impactAfter, "$.affected_waiting_tickets")).as("the ticket still carries the now inactive class").isEqualTo(1);
    }

    @Test
    void priorityClassVersionEndpointsAreCheckedOnTheServer() throws Exception {
        Setup s = setup("C");
        UUID classId = UUID.fromString((String) field(call(post("/api/v1/priority-classes"), token(Role.ORG_ADMIN, s.site()), classBody("X", 5, null, null)), "$.id"));
        String agent = token(Role.AGENT, s.site());
        assertThat(status(call(get("/api/v1/priority-classes/" + classId + "/impact"), agent, null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/priority-classes/" + classId + "/versions/" + UUID.randomUUID() + "/revert"), agent, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/priority-classes/" + classId + "/versions"), null, null))).isEqualTo(401);
    }

    // ---- FR-CFG-040: routing strategy versioning and revert ------------------------------------------------------

    @Test
    void aRoutingStrategyChangeIsVersionedAndRevertibleAndWarnsAboutTheGroupsWaitingTickets() throws Exception {
        Setup s = setup("D");
        String admin = token(Role.ORG_ADMIN, s.site());
        issueWaiting(s.service(), null);
        issueWaiting(s.service(), null);

        MvcResult impact = call(get("/api/v1/service-groups/" + s.group() + "/routing-strategy/impact"), admin, null);
        assertThat((Integer) field(impact, "$.affected_waiting_tickets")).isEqualTo(2);

        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"strict_priority\"}");
        clock.set(BASE.plusSeconds(1));
        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"fifo\"}");

        MvcResult history = call(get("/api/v1/service-groups/" + s.group() + "/routing-strategy/versions"), admin, null);
        assertThat((List<String>) (List<?>) field(history, "$.items[*].payload.strategy")).containsExactly("fifo", "strict_priority");
        UUID firstVersionId = UUID.fromString((String) field(history, "$.items[1].id"));

        clock.set(BASE.plusSeconds(2));
        MvcResult reverted = call(post("/api/v1/service-groups/" + s.group() + "/routing-strategy/versions/" + firstVersionId + "/revert"), admin, null);
        assertThat(status(reverted)).as(body(reverted)).isEqualTo(200);
        assertThat((String) field(reverted, "$.strategy")).isEqualTo("strict_priority");
    }

    // ---- FR-CFG-040: numbering rule versioning, including across removal ------------------------------------------

    @Test
    void aNumberingRuleChangeIsVersionedSurvivesRemovalAndCanBeRevertedBackToPriorContent() throws Exception {
        Setup s = setup("E");
        String admin = token(Role.ORG_ADMIN, s.site());

        call(put("/api/v1/services/" + s.service() + "/numbering-rule"), admin, "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"AA\",\"padding\":2}");
        clock.set(BASE.plusSeconds(1));
        call(put("/api/v1/services/" + s.service() + "/numbering-rule"), admin, "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"BB\",\"padding\":3}");

        MvcResult history = call(get("/api/v1/services/" + s.service() + "/numbering-rule/versions"), admin, null);
        assertThat(status(history)).as(body(history)).isEqualTo(200);
        assertThat((List<String>) (List<?>) field(history, "$.items[*].payload.fixed_prefix")).containsExactly("BB", "AA");
        UUID firstVersionId = UUID.fromString((String) field(history, "$.items[1].id"));

        clock.set(BASE.plusSeconds(2));
        assertThat(status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null))).isEqualTo(200);

        MvcResult historyAfterDelete = call(get("/api/v1/services/" + s.service() + "/numbering-rule/versions"), admin, null);
        assertThat(status(historyAfterDelete)).as(body(historyAfterDelete)).isEqualTo(200);
        assertThat((Boolean) field(historyAfterDelete, "$.items[0].payload.deleted")).isTrue();

        clock.set(BASE.plusSeconds(3));
        MvcResult reverted = call(post("/api/v1/services/" + s.service() + "/numbering-rule/versions/" + firstVersionId + "/revert"), admin, null);
        assertThat(status(reverted)).as(body(reverted)).isEqualTo(200);
        assertThat((String) field(reverted, "$.rule.fixed_prefix")).isEqualTo("AA");
        assertThat((String) field(call(get("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null), "$.fixed_prefix")).isEqualTo("AA");
    }

    // ---- FR-CFG-040: business hours versioning, revert and impact -------------------------------------------------

    @Test
    void aBusinessHoursChangeIsVersionedRevertibleAndWarnsAboutTheSitesWaitingTickets() throws Exception {
        Setup s = setup("F");
        String admin = token(Role.ORG_ADMIN, s.site());
        issueWaiting(s.service(), null);

        String monToFri = "{\"days\":[{\"weekday\":1,\"open\":\"09:00\",\"close\":\"17:00\"}]}";
        String monToSat = "{\"days\":[{\"weekday\":1,\"open\":\"09:00\",\"close\":\"17:00\"},{\"weekday\":6,\"open\":\"09:00\",\"close\":\"13:00\"}]}";
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, monToFri);

        MvcResult impact = call(get("/api/v1/sites/" + s.site() + "/hours/impact"), admin, null);
        assertThat(status(impact)).as(body(impact)).isEqualTo(200);
        assertThat((Integer) field(impact, "$.affected_waiting_tickets")).isEqualTo(1);

        clock.set(BASE.plusSeconds(1));
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, monToSat);

        MvcResult history = call(get("/api/v1/sites/" + s.site() + "/hours/versions"), admin, null);
        assertThat(status(history)).as(body(history)).isEqualTo(200);
        List<Object> days0 = field(history, "$.items[0].payload.days");
        List<Object> days1 = field(history, "$.items[1].payload.days");
        assertThat(days0).hasSize(2);
        assertThat(days1).hasSize(1);
        UUID firstVersionId = UUID.fromString((String) field(history, "$.items[1].id"));

        clock.set(BASE.plusSeconds(2));
        MvcResult reverted = call(post("/api/v1/sites/" + s.site() + "/hours/versions/" + firstVersionId + "/revert"), admin, null);
        assertThat(status(reverted)).as(body(reverted)).isEqualTo(200);
        assertThat((List<Object>) field(reverted, "$.days")).hasSize(1);
    }

    // ---- config.changed reaches the device fleet -------------------------------------------------------------------

    @Test
    void aSiteScopedConfigChangeReachesTheSitesActiveDevicesOnly() throws Exception {
        Setup here = setup("G");
        Setup elsewhere = setup("H");
        UUID device = newDevice(here.site());
        newDevice(elsewhere.site()); // not expected to hear a change scoped to `here`
        String admin = token(Role.ORG_ADMIN, here.site(), elsewhere.site());

        call(put("/api/v1/services/" + here.service() + "/numbering-rule"), admin, "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"ZZ\"}");

        assertThat(recorder.published).as("only the affected Site's device heard it")
                .containsExactly(new Recorder.Published("device:" + device, "config.changed"));
    }

    @Test
    void anOrganisationWideChangeReachesDevicesOfEverySite() throws Exception {
        Setup a = setup("I");
        Setup b = setup("J");
        UUID deviceA = newDevice(a.site());
        UUID deviceB = newDevice(b.site());
        String admin = token(Role.ORG_ADMIN, a.site(), b.site());

        call(post("/api/v1/priority-classes"), admin, classBody("Org wide", 5, null, null));

        assertThat(recorder.published.stream().map(Recorder.Published::topic))
                .as("a Priority class is organisation-wide, so every Site's device heard it")
                .contains("device:" + deviceA, "device:" + deviceB);
    }

    // ---- CFG-004: the signed configuration bundle --------------------------------------------------------------

    @Test
    void theSignedBundleExportsAndReimportsTheFourVersionedConfigAreasCleanly() throws Exception {
        Setup s = setup("K");
        String admin = token(Role.SYSTEM_ADMIN);
        UUID classId = UUID.fromString((String) field(call(post("/api/v1/priority-classes"), admin, classBody("Bundle class", 15, 90, "BC")), "$.id"));
        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"fifo\"}");
        call(put("/api/v1/services/" + s.service() + "/numbering-rule"), admin, "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"BD\",\"padding\":4}");
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":2,\"open\":\"08:00\",\"close\":\"16:00\"}]}");

        MvcResult exported = call(get("/api/v1/config/bundle"), admin, null);
        assertThat(status(exported)).as(body(exported)).isEqualTo(200);
        String payloadJson = field(exported, "$.payload_json");
        String signature = field(exported, "$.signature");
        assertThat(payloadJson).contains("BC").contains("fifo").contains("BD").contains("08:00");

        // Change everything away from what was exported, so the import has visible work to do.
        call(put("/api/v1/priority-classes/" + classId), admin, classBody("Bundle class", 15, 30, "BC"));
        call(put("/api/v1/service-groups/" + s.group() + "/routing-strategy"), admin, "{\"strategy\":\"strict_priority\"}");
        call(put("/api/v1/services/" + s.service() + "/numbering-rule"), admin, "{\"prefix_source\":\"fixed\",\"fixed_prefix\":\"XX\"}");
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":3,\"open\":\"10:00\",\"close\":\"12:00\"}]}");

        String importBody = importBody(payloadJson, signature);
        MvcResult imported = call(post("/api/v1/config/bundle/import"), admin, importBody);
        assertThat(status(imported)).as(body(imported)).isEqualTo(200);
        assertThat((Integer) field(imported, "$.applied_counts.priority_classes")).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT max_wait_minutes FROM priority_class WHERE id = ?", Integer.class, classId))
                .as("the class is back to what the bundle carried, not the value set after export").isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT strategy FROM routing_strategy WHERE service_group_id = ?", String.class, s.group())).isEqualTo("fifo");
        assertThat((String) field(call(get("/api/v1/services/" + s.service() + "/numbering-rule"), admin, null), "$.fixed_prefix")).isEqualTo("BD");
        assertThat((List<Object>) field(call(get("/api/v1/sites/" + s.site() + "/hours"), admin, null), "$.days")).hasSize(1);
    }

    @Test
    void aTamperedBundleSignatureIsRejected() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        MvcResult exported = call(get("/api/v1/config/bundle"), admin, null);
        String payloadJson = field(exported, "$.payload_json");

        String tampered = importBody(payloadJson, "00");
        MvcResult refused = call(post("/api/v1/config/bundle/import"), admin, tampered);
        assertThat(status(refused)).isEqualTo(400);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("invalid_signature");
    }

    @Test
    void bundleEndpointsRequireAnOrganisationWideAdmin() throws Exception {
        Setup s = setup("L");
        String scoped = token(Role.ORG_ADMIN, s.site());
        assertThat(status(call(get("/api/v1/config/bundle"), scoped, null))).as("scoped admin, not organisation-wide").isEqualTo(403);
        String agent = token(Role.AGENT, s.site());
        assertThat(status(call(get("/api/v1/config/bundle"), agent, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/config/bundle"), null, null))).isEqualTo(401);
    }
}
