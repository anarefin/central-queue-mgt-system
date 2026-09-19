package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
 * Ticket 07 over HTTP against real PostgreSQL: a Reception Operator issues a walk-in ticket and it appears in the
 * service's queue (FR-ISS-001, FR-ISS-002, FR-QUE-201, FR-QUE-070, SRS §4.4, §8.5, §18.4, §18.5, §20.1, §20.5, Invariant 3,
 * ADR-0001, ADR-0006, ADR-0007).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class IssuanceIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-issuance");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired SequenceBlocks sequences;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    /** A site with one zone, a counter in it, one group and one service that issues at reception and the kiosk. */
    private record Setup(UUID site, UUID zone, UUID counter, UUID group, UUID service) {}

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newZone(UUID site, String name, String building, String floor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, building_label, floor_label) VALUES (?, ?, ?, ?, ?)", id, site, name, building, floor);
        return id;
    }

    private UUID newCounter(UUID zone, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, zone, label);
        return id;
    }

    private UUID newGroup(UUID site, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\",\"bn\":\"বহির্বিভাগ\"}'::jsonb, ?)", id, site, prefix);
        return id;
    }

    private UUID newService(UUID group, String prefix, String channelsJson, String bookingMode, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, ?::jsonb, ?, ?)",
                id, group, prefix, channelsJson, bookingMode, active);
        return id;
    }

    private void link(UUID counter, UUID service, int weight) {
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, ?)", counter, service, weight);
    }

    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID zone = newZone(site, "Ground waiting", "Block A", "Ground");
        UUID counter = newCounter(zone, "1");
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix, "[\"reception\",\"kiosk\"]", "both", true);
        link(counter, service, 1);
        return new Setup(site, zone, counter, group, service);
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private record Login(UUID user, String token) {}

    private Login login(Role role, UUID[] sites, UUID[] groups) throws Exception {
        UUID user = createUser(role.wire());
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", sites));
            ps.setArray(5, connection.createArrayOf("uuid", groups));
            return ps;
        });
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return new Login(user, JsonPath.read(result.getResponse().getContentAsString(), "$.access_token"));
    }

    private String token(Role role, UUID... sites) throws Exception {
        return login(role, sites, new UUID[0]).token();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private MvcResult issue(String token, String key, UUID service) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tickets");
        if (key != null) request.header("Idempotency-Key", key);
        return call(request, token, "{\"service_id\":\"" + service + "\",\"origin_channel\":\"reception\"}");
    }

    private MvcResult issueOk(String token, UUID service) throws Exception {
        MvcResult result = issue(token, UUID.randomUUID().toString(), service);
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return result;
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

    private static String errorCode(MvcResult result) throws Exception {
        return field(result, "$.error.code");
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- idempotency (§20.1) -----------------------------------------------------------------------------------

    @Test
    void issuingWithoutAnIdempotencyKeyIsRefusedAndIssuesNothing() throws Exception {
        Setup s = setup("A");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult missing = issue(reception, null, s.service());
        MvcResult blank = issue(reception, "  ", s.service());

        assertThat(status(missing)).isEqualTo(400);
        assertThat(errorCode(missing)).isEqualTo("validation_failed");
        assertThat((String) field(missing, "$.error.details.fields[0].field")).isEqualTo("Idempotency-Key");
        assertThat(status(blank)).isEqualTo(400);
        assertThat(count("ticket", "service_id = ?", s.service())).isZero();
    }

    @Test
    void replayingAKeyWithinTwentyFourHoursReturnsTheOriginalTicketAndIssuesNothingMore() throws Exception {
        Setup s = setup("R");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        String key = UUID.randomUUID().toString();

        MvcResult first = issue(reception, key, s.service());
        MvcResult replay = issue(reception, key, s.service());

        assertThat(status(first)).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(status(replay)).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay)).as("the original result, secret included").isEqualTo(body(first));
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(1);
        assertThat(count("ticket_event", "ticket_id = ?", UUID.fromString(field(first, "$.id")))).isEqualTo(1);

        MvcResult another = issue(reception, UUID.randomUUID().toString(), s.service());
        assertThat((String) field(another, "$.token_number")).isEqualTo("R-002");
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(2);
    }

    @Test
    void aKeyIsPerCallerAndCannotBeReusedForADifferentRequest() throws Exception {
        Setup s = setup("K");
        UUID other = newService(s.group(), "L", "[\"reception\"]", "both", true);
        String one = token(Role.RECEPTION_OPERATOR, s.site());
        String two = token(Role.RECEPTION_OPERATOR, s.site());
        String key = UUID.randomUUID().toString();

        MvcResult first = issue(one, key, s.service());
        MvcResult differentRequest = issue(one, key, other);
        MvcResult differentCaller = issue(two, key, s.service());

        assertThat(status(first)).isEqualTo(201);
        assertThat(status(differentRequest)).isEqualTo(409);
        assertThat(errorCode(differentRequest)).isEqualTo("conflict");
        assertThat((String) field(differentRequest, "$.error.details.reason")).isEqualTo("idempotency_key_reused");
        assertThat(count("ticket", "service_id = ?", other)).isZero();
        assertThat(status(differentCaller)).isEqualTo(201);
        assertThat((String) field(differentCaller, "$.id")).isNotEqualTo(field(first, "$.id"));
    }

    @Test
    void aKeyOlderThanTwentyFourHoursIssuesAgain() throws Exception {
        Setup s = setup("O");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        String key = UUID.randomUUID().toString();
        MvcResult first = issue(reception, key, s.service());
        jdbc.update("UPDATE idempotency_key SET created_at = created_at - interval '25 hours' WHERE idem_key = ?", key);

        MvcResult again = issue(reception, key, s.service());

        assertThat(status(again)).isEqualTo(201);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat((String) field(again, "$.id")).isNotEqualTo(field(first, "$.id"));
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(2);
    }

    @Test
    void simultaneousRequestsWithOneKeyIssueExactlyOneTicket() throws Exception {
        Setup s = setup("C");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        String key = UUID.randomUUID().toString();

        List<MvcResult> results = inParallel(8, i -> issue(reception, key, s.service()));

        assertThat(results).allSatisfy(r -> assertThat(status(r)).as(body(r)).isEqualTo(201));
        assertThat(results.stream().map(r -> readId(r)).distinct()).hasSize(1);
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(1);
    }

    private static String readId(MvcResult r) {
        try {
            return field(r, "$.id");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- token numbers and sequence blocks (FR-QUE-201, §4.4, §18.4) ------------------------------------------

    @Test
    void simultaneousIssuesGetDistinctConsecutiveTokenNumbers() throws Exception {
        Setup s = setup("P");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        List<MvcResult> results = inParallel(12, i -> issue(reception, UUID.randomUUID().toString(), s.service()));

        assertThat(results).allSatisfy(r -> assertThat(status(r)).as(body(r)).isEqualTo(201));
        Set<String> tokens = new TreeSet<>();
        for (MvcResult r : results) tokens.add(field(r, "$.token_number"));
        assertThat(tokens).containsExactly("P-001", "P-002", "P-003", "P-004", "P-005", "P-006", "P-007", "P-008", "P-009", "P-010", "P-011", "P-012");
    }

    @Test
    void theDefaultRuleIsPrefixDashPaddedSequenceWithADailyResetKeyInTheSiteTimezone() throws Exception {
        Setup s = setup("N");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID other = newService(s.group(), "Q", "[\"reception\"]", "both", true);

        MvcResult one = issueOk(reception, s.service());
        MvcResult two = issueOk(reception, s.service());
        MvcResult third = issueOk(reception, other);

        assertThat((String) field(one, "$.token_number")).isEqualTo("N-001");
        assertThat((String) field(two, "$.token_number")).isEqualTo("N-002");
        assertThat((String) field(third, "$.token_number")).as("each prefix counts on its own").isEqualTo("Q-001");
        String today = LocalDate.now(ZoneId.of("Asia/Dhaka")).toString();
        Map<String, Object> row = jdbc.queryForMap("SELECT reset_key, sequence_no FROM ticket WHERE id = ?", UUID.fromString(field(two, "$.id")));
        assertThat(row.get("reset_key")).isIn(today, LocalDate.now(ZoneId.of("Asia/Dhaka")).minusDays(1).toString(), LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1).toString());
        assertThat(((Number) row.get("sequence_no")).longValue()).isEqualTo(2);
    }

    @Test
    void sequencesRestartForANewResetKeyAndNewBlocksOpenWhenOneIsUsedUp() {
        UUID site = newSite();
        List<Long> day1 = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < SequenceBlocks.BLOCK_SIZE + 5; i++) day1.add(sequences.next(site, "Z", "2026-09-19"));
        });
        List<Long> day2 = new ArrayList<>();
        transactions.executeWithoutResult(status -> day2.add(sequences.next(site, "Z", "2026-09-20")));

        assertThat(day1.getFirst()).isEqualTo(1);
        assertThat(day1).as("continuous across the block boundary").isSorted().doesNotHaveDuplicates().last().isEqualTo((long) SequenceBlocks.BLOCK_SIZE + 5);
        assertThat(day2).as("a new reset period restarts at one").containsExactly(1L);
        assertThat(count("sequence_block", "site_id = ? AND reset_key = '2026-09-19'", site)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT block_end FROM sequence_block WHERE site_id = ? AND reset_key = '2026-09-19' ORDER BY block_start LIMIT 1", Integer.class, site))
                .isEqualTo(SequenceBlocks.BLOCK_SIZE);
    }

    @Test
    void tokenNumbersAreUniquePerSiteAndResetKeyForChainHeadsOnly() throws Exception {
        Setup s = setup("U");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID head = UUID.fromString(field(issueOk(reception, s.service()), "$.id"));
        Map<String, Object> row = jdbc.queryForMap("SELECT token_number, reset_key, visit_id FROM ticket WHERE id = ?", head);

        String insert = "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, visit_id, predecessor_ticket_id,"
                + " origin_channel, state, issued_at, queued_at, secret_hash) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, 'reception', 'waiting', now(), now(), 'x')";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), row.get("token_number"), row.get("reset_key"), s.service(), s.group(), s.site(), row.get("visit_id"), null))
                .as("a second chain head with the same number").isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.update(insert, UUID.randomUUID(), row.get("token_number"), row.get("reset_key"), s.service(), s.group(), s.site(), row.get("visit_id"), head))
                .as("a successor reuses its chain head's number (ADR-0006)").isEqualTo(1);
        assertThat(jdbc.update(insert, UUID.randomUUID(), row.get("token_number"), "2000-01-01", s.service(), s.group(), s.site(), row.get("visit_id"), null))
                .as("the same number on another day").isEqualTo(1);
    }

    // ---- atomicity (FR-ISS-001) --------------------------------------------------------------------------------

    @Test
    void aFailureAfterTheNumberIsDrawnLeavesNoTicketVisitNumberOrKeyBehind() throws Exception {
        UUID site = newSite();
        UUID zone = newZone(site, "Lobby", null, "1st");
        UUID group = newGroup(site, "GF");
        UUID service = newService(group, "FAIL", "[\"reception\"]", "both", true);
        link(newCounter(zone, "1"), service, 1);
        String reception = token(Role.RECEPTION_OPERATOR, site);
        String key = UUID.randomUUID().toString();

        jdbc.execute("CREATE OR REPLACE FUNCTION test_reject_fail_events() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_events BEFORE INSERT ON ticket_event FOR EACH ROW WHEN (NEW.payload->>'token_number' LIKE 'FAIL-%')"
                + " EXECUTE FUNCTION test_reject_fail_events()");
        MvcResult failed;
        try {
            failed = issue(reception, key, service);
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_events ON ticket_event");
        }

        assertThat(status(failed)).isEqualTo(500);
        assertThat(count("ticket", "service_id = ?", service)).isZero();
        assertThat(count("visit", "site_id = ?", site)).isZero();
        assertThat(count("sequence_block", "site_id = ?", site)).as("the drawn number went back").isZero();
        assertThat(count("idempotency_key", "idem_key = ?", key)).as("a failed request leaves no claim").isZero();

        MvcResult retry = issue(reception, key, service);
        assertThat(status(retry)).isEqualTo(201);
        assertThat((String) field(retry, "$.token_number")).as("no gap in the numbers").isEqualTo("FAIL-001");
    }

    // ---- the response and the queue (FR-ISS-002, §20.5) --------------------------------------------------------

    @Test
    void theResponseCarriesEverythingTheVisitorNeedsAndTheSecretOnlyOnce() throws Exception {
        Setup s = setup("H");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult first = issueOk(reception, s.service());
        MvcResult second = issueOk(reception, s.service());

        assertThat((String) field(first, "$.token_number")).isEqualTo("H-001");
        assertThat((String) field(first, "$.state")).isEqualTo("waiting");
        assertThat((String) field(first, "$.service.id")).isEqualTo(s.service().toString());
        assertThat((String) field(first, "$.service.name_i18n.en")).isEqualTo("Consultation");
        assertThat((String) field(first, "$.service.name_i18n.bn")).isEqualTo("পরামর্শ");
        assertThat((String) field(first, "$.service_group.name_i18n.en")).isEqualTo("Outpatient");
        assertThat((String) field(first, "$.zone.id")).isEqualTo(s.zone().toString());
        assertThat((String) field(first, "$.zone.name")).isEqualTo("Ground waiting");
        assertThat((String) field(first, "$.zone.building_label")).isEqualTo("Block A");
        assertThat((String) field(first, "$.zone.floor_label")).isEqualTo("Ground");
        assertThat((Integer) field(first, "$.position")).isEqualTo(1);
        assertThat((Integer) field(second, "$.position")).isEqualTo(2);
        // A rounded range, never one figure (FR-QUE-042, FR-ISS-005): nobody ahead of the first, one ticket at the service's expected 10 minutes ahead of the second.
        assertThat((Integer) field(first, "$.estimated_wait_minutes.low")).isEqualTo(0);
        assertThat((Integer) field(first, "$.estimated_wait_minutes.high")).isEqualTo(5);
        assertThat((Integer) field(second, "$.estimated_wait_minutes.low")).isEqualTo(10);
        assertThat((Integer) field(second, "$.estimated_wait_minutes.high")).isEqualTo(15);
        assertThat((String) field(first, "$.origin_channel")).isEqualTo("reception");
        assertThat((String) field(first, "$.visit_id")).isNotBlank();
        assertThat((String) field(first, "$.issued_at")).isNotBlank();
        String secret = field(first, "$.secret");
        assertThat(secret).hasSizeGreaterThanOrEqualTo(32);

        UUID id = UUID.fromString(field(first, "$.id"));
        assertThat(jdbc.queryForObject("SELECT secret_hash FROM ticket WHERE id = ?", String.class, id)).isEqualTo(sha256(secret)).isNotEqualTo(secret);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket t WHERE t.id = ? AND t::text LIKE ?", Integer.class, id, "%" + secret + "%"))
                .as("the secret itself is nowhere in the row").isZero();

        MvcResult fetched = call(get("/api/v1/tickets/" + id), reception, null);
        assertThat(status(fetched)).isEqualTo(200);
        assertThat((String) field(fetched, "$.token_number")).isEqualTo("H-001");
        assertThat((Integer) field(fetched, "$.position")).isEqualTo(1);
        assertThat(body(fetched)).as("the secret cannot be read back").doesNotContain("secret").doesNotContain(secret);
    }

    @Test
    void theIssuedTicketAppearsInTheServicesQueueSnapshotInOrder() throws Exception {
        Setup s = setup("Q");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult empty = call(get("/api/v1/queues/" + s.service()), reception, null);
        assertThat(status(empty)).isEqualTo(200);
        assertThat((Integer) field(empty, "$.waiting_count")).isZero();
        assertThat((List<?>) field(empty, "$.tickets")).isEmpty();

        String first = field(issueOk(reception, s.service()), "$.id");
        String second = field(issueOk(reception, s.service()), "$.id");

        MvcResult snapshot = call(get("/api/v1/queues/" + s.service()), reception, null);
        assertThat(status(snapshot)).isEqualTo(200);
        assertThat((Integer) field(snapshot, "$.waiting_count")).isEqualTo(2);
        assertThat((String) field(snapshot, "$.service.id")).isEqualTo(s.service().toString());
        assertThat((List<String>) field(snapshot, "$.tickets[*].id")).containsExactly(first, second);
        assertThat((List<String>) field(snapshot, "$.tickets[*].token_number")).containsExactly("Q-001", "Q-002");
        assertThat((List<Integer>) field(snapshot, "$.tickets[*].position")).containsExactly(1, 2);
        assertThat((List<String>) field(snapshot, "$.tickets[*].state")).containsOnly("waiting");

        MvcResult limited = call(get("/api/v1/queues/" + s.service() + "?limit=1"), reception, null);
        assertThat((List<String>) field(limited, "$.tickets[*].id")).containsExactly(first);
        assertThat((Integer) field(limited, "$.waiting_count")).isEqualTo(2);
        assertThat(status(call(get("/api/v1/queues/" + s.service() + "?limit=0"), reception, null))).isEqualTo(400);
        assertThat(status(call(get("/api/v1/queues/" + UUID.randomUUID()), reception, null))).isEqualTo(404);
        assertThat(status(call(get("/api/v1/tickets/" + UUID.randomUUID()), reception, null))).isEqualTo(404);
    }

    @Test
    void theSiteServiceListShowsWhatReceptionCanIssueWithLiveQueueLengths() throws Exception {
        Setup s = setup("L");
        newService(s.group(), "KO", "[\"kiosk\"]", "both", true);
        newService(s.group(), "IN", "[\"reception\"]", "both", false);
        newService(s.group(), "AP", "[\"reception\"]", "appointment_only", true);
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        issueOk(reception, s.service());

        MvcResult all = call(get("/api/v1/sites/" + s.site() + "/services"), reception, null);
        MvcResult atReception = call(get("/api/v1/sites/" + s.site() + "/services?channel=reception"), reception, null);

        assertThat(status(atReception)).as(body(atReception)).isEqualTo(200);
        assertThat((String) field(atReception, "$.default_language")).isEqualTo("en");
        assertThat((List<String>) field(atReception, "$.items[*].token_prefix")).containsExactly("L");
        assertThat((Integer) field(atReception, "$.items[0].waiting_count")).isEqualTo(1);
        assertThat((String) field(atReception, "$.items[0].service_group.name_i18n.en")).isEqualTo("Outpatient");
        assertThat((List<String>) field(all, "$.items[*].token_prefix")).containsExactlyInAnyOrder("L", "KO", "AP");
        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/services?channel=fax"), reception, null))).isEqualTo(400);
        assertThat(status(call(get("/api/v1/sites/" + UUID.randomUUID() + "/services"), token(Role.ORG_ADMIN), null))).isEqualTo(404);
    }

    // ---- visit, denormalisation and events (ADR-0007, §18.5, Invariant 3) --------------------------------------

    @Test
    void everyTicketBelongsToAVisitCreatedWithItsFirstTicket() throws Exception {
        Setup s = setup("V");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult issued = issueOk(reception, s.service());

        UUID id = UUID.fromString(field(issued, "$.id"));
        UUID visitId = jdbc.queryForObject("SELECT visit_id FROM ticket WHERE id = ?", UUID.class, id);
        assertThat(visitId).isNotNull();
        assertThat(visitId.toString()).isEqualTo(field(issued, "$.visit_id"));
        assertThat(jdbc.queryForObject("SELECT site_id FROM visit WHERE id = ?", UUID.class, visitId)).isEqualTo(s.site());
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'ticket' AND column_name = 'visit_id'", String.class))
                .as("visit_id is NOT NULL in the schema").isEqualTo("NO");
        assertThat(count("visit", "site_id = ?", s.site())).isEqualTo(1);
    }

    @Test
    void aTicketCopiesItsGroupSiteAndZoneAtIssueAndLaterReconfigurationLeavesItAlone() throws Exception {
        Setup s = setup("D");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult issued = issueOk(reception, s.service());
        UUID id = UUID.fromString(field(issued, "$.id"));

        Map<String, Object> row = jdbc.queryForMap("SELECT service_group_id, site_id, zone_id FROM ticket WHERE id = ?", id);
        assertThat(row.get("service_group_id")).isEqualTo(s.group());
        assertThat(row.get("site_id")).isEqualTo(s.site());
        assertThat(row.get("zone_id")).isEqualTo(s.zone());

        UUID newZone = newZone(s.site(), "Upstairs", null, "2nd");
        UUID newCounter = newCounter(newZone, "9");
        jdbc.update("DELETE FROM counter_service WHERE service_id = ?", s.service());
        link(newCounter, s.service(), 1);
        jdbc.update("UPDATE zone SET name = 'Renamed hall' WHERE id = ?", s.zone());

        MvcResult fetched = call(get("/api/v1/tickets/" + id), reception, null);
        assertThat((String) field(fetched, "$.zone.id")).as("the ticket still points at the zone it was issued into").isEqualTo(s.zone().toString());
        MvcResult next = issueOk(reception, s.service());
        assertThat((String) field(next, "$.zone.id")).as("a new ticket follows the new configuration").isEqualTo(newZone.toString());
    }

    @Test
    void aServiceNoCounterServesYetStillIssuesWithoutAZone() throws Exception {
        Setup s = setup("W");
        jdbc.update("DELETE FROM counter_service WHERE service_id = ?", s.service());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult issued = issueOk(reception, s.service());

        assertThat(JsonPath.<Object>read(body(issued), "$.zone")).isNull();
    }

    @Test
    void issuingWritesExactlyOneEventWithDeviceAndServerTimeAndSequenceNumberOne() throws Exception {
        Setup s = setup("E");
        Login reception = login(Role.RECEPTION_OPERATOR, new UUID[] {s.site()}, new UUID[0]);
        Instant deviceTime = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        MvcResult withDeviceTime = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception.token(),
                "{\"service_id\":\"" + s.service() + "\",\"occurred_at\":\"" + deviceTime + "\"}");
        MvcResult withoutDeviceTime = issueOk(reception.token(), s.service());

        assertThat(status(withDeviceTime)).as(body(withDeviceTime)).isEqualTo(201);
        UUID id = UUID.fromString(field(withDeviceTime, "$.id"));
        assertThat(count("ticket_event", "ticket_id = ?", id)).isEqualTo(1);
        Map<String, Object> event = jdbc.queryForMap("SELECT seq, event_type, from_state, to_state, actor_id, actor_type, counter_id, occurred_at, recorded_at, payload::text AS payload FROM ticket_event WHERE ticket_id = ?", id);
        assertThat(((Number) event.get("seq")).intValue()).isEqualTo(1);
        assertThat(event.get("event_type")).isEqualTo("ticket.issued");
        assertThat(event.get("from_state")).isNull();
        assertThat(event.get("to_state")).isEqualTo("waiting");
        assertThat(event.get("actor_id")).isEqualTo(reception.user());
        assertThat(event.get("actor_type")).isEqualTo("staff");
        assertThat(((java.sql.Timestamp) event.get("occurred_at")).toInstant()).as("the device's time").isEqualTo(deviceTime);
        assertThat(((java.sql.Timestamp) event.get("recorded_at")).toInstant()).as("the server's time").isAfter(deviceTime.plusSeconds(60));
        assertThat((String) event.get("payload")).contains("reception").contains("E-001");

        UUID other = UUID.fromString(field(withoutDeviceTime, "$.id"));
        Map<String, Object> defaulted = jdbc.queryForMap("SELECT occurred_at, recorded_at FROM ticket_event WHERE ticket_id = ?", other);
        assertThat(defaulted.get("occurred_at")).as("with no device time the server's is recorded for both").isEqualTo(defaulted.get("recorded_at"));
    }

    @Test
    void ticketEventsAreAppendOnly() throws Exception {
        Setup s = setup("T");
        UUID id = UUID.fromString(field(issueOk(token(Role.RECEPTION_OPERATOR, s.site()), s.service()), "$.id"));

        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_event SET event_type = 'x' WHERE ticket_id = ?", id)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ticket_event WHERE ticket_id = ?", id)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void issuingIsAuditedWithTheActorAndTheTicketDetails() throws Exception {
        Setup s = setup("X");
        Login reception = login(Role.RECEPTION_OPERATOR, new UUID[] {s.site()}, new UUID[0]);

        UUID id = UUID.fromString(field(issueOk(reception.token(), s.service()), "$.id"));

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, actor_role, after::text AS after FROM audit_log WHERE action = 'ticket.issued' AND entity_id = ?", id);
        assertThat(audit.get("actor_id")).isEqualTo(reception.user());
        assertThat(audit.get("actor_role")).isEqualTo("reception_operator");
        assertThat((String) audit.get("after")).contains("X-001").contains(s.service().toString()).contains("reception");
    }

    // ---- one issuance path for every channel (§8.5) ------------------------------------------------------------

    @Test
    void theIssuanceServiceIsChannelAgnosticAndRecordsTheChannelAndActor() {
        Setup s = setup("M");
        UUID device = UUID.randomUUID();

        TicketResponse kiosk = issuance.issue(new IssueCommand(s.service(), Channels.KIOSK, device, ActorType.DEVICE, Instant.now().minusSeconds(60)));
        TicketResponse desk = issuance.issue(new IssueCommand(s.service(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));

        assertThat(kiosk.originChannel()).isEqualTo("kiosk");
        assertThat(kiosk.tokenNumber()).isEqualTo("M-001");
        assertThat(desk.tokenNumber()).as("one sequence across channels").isEqualTo("M-002");
        assertThat(jdbc.queryForObject("SELECT origin_channel FROM ticket WHERE id = ?", String.class, kiosk.id())).isEqualTo("kiosk");
        assertThat(jdbc.queryForObject("SELECT actor_type FROM ticket_event WHERE ticket_id = ?", String.class, kiosk.id())).isEqualTo("device");
        assertThat(kiosk.secret()).isNotBlank();
    }

    @Test
    void theHttpEndpointIssuesAtReceptionOnly() throws Exception {
        Setup s = setup("Y");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult kiosk = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception,
                "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"kiosk\"}");
        MvcResult unknown = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception,
                "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"fax\"}");
        MvcResult noService = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception, "{}");
        MvcResult defaulted = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception, "{\"service_id\":\"" + s.service() + "\"}");

        assertThat(status(kiosk)).isEqualTo(403);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(status(noService)).isEqualTo(400);
        assertThat(status(defaulted)).isEqualTo(201);
        assertThat((String) field(defaulted, "$.origin_channel")).isEqualTo("reception");
    }

    @Test
    void anInactiveServiceOrOneNotIssuedAtReceptionCannotBeIssuedFor() throws Exception {
        Setup s = setup("Z");
        UUID inactive = newService(s.group(), "ZI", "[\"reception\"]", "both", false);
        UUID kioskOnly = newService(s.group(), "ZK", "[\"kiosk\"]", "both", true);
        UUID appointments = newService(s.group(), "ZA", "[\"reception\"]", "appointment_only", true);
        UUID inactiveGroupService = newService(newGroup(s.site(), "ZG"), "ZX", "[\"reception\"]", "both", true);
        jdbc.update("UPDATE service_group SET active = false WHERE id = (SELECT service_group_id FROM service WHERE id = ?)", inactiveGroupService);
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        assertThat(reasonOf(issue(reception, UUID.randomUUID().toString(), inactive))).isEqualTo("service_inactive");
        assertThat(reasonOf(issue(reception, UUID.randomUUID().toString(), inactiveGroupService))).isEqualTo("service_inactive");
        assertThat(reasonOf(issue(reception, UUID.randomUUID().toString(), kioskOnly))).isEqualTo("channel_not_allowed");
        assertThat(reasonOf(issue(reception, UUID.randomUUID().toString(), appointments))).isEqualTo("appointment_only");
        assertThat(status(issue(reception, UUID.randomUUID().toString(), UUID.randomUUID()))).isEqualTo(404);
        assertThat(count("ticket", "site_id = ?", s.site())).isZero();
    }

    private String reasonOf(MvcResult refused) throws Exception {
        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat(errorCode(refused)).isEqualTo("conflict");
        return field(refused, "$.error.details.reason");
    }

    // ---- permissions and scope (§5.2, FR-CFG-103, FR-CFG-106) --------------------------------------------------

    @Test
    void onlyReceptionMayIssueAndEveryStaffRoleMayReadTicketsAndQueues() throws Exception {
        Setup s = setup("B");
        UUID ticket = UUID.fromString(field(issueOk(token(Role.RECEPTION_OPERATOR, s.site()), s.service()), "$.id"));

        for (Role role : Role.values()) {
            String token = token(role);
            boolean issues = role == Role.RECEPTION_OPERATOR;
            assertThat(status(issue(token, UUID.randomUUID().toString(), s.service()))).as(role + " issue").isEqualTo(issues ? 201 : 403);
            assertThat(status(call(get("/api/v1/tickets/" + ticket), token, null))).as(role + " get ticket").isEqualTo(200);
            assertThat(status(call(get("/api/v1/queues/" + s.service()), token, null))).as(role + " queue").isEqualTo(200);
            assertThat(status(call(get("/api/v1/sites/" + s.site() + "/services"), token, null))).as(role + " services").isEqualTo(200);
        }
        assertThat(status(issue(null, UUID.randomUUID().toString(), s.service()))).isEqualTo(401);
        assertThat(status(call(get("/api/v1/tickets/" + ticket), null, null))).isEqualTo(401);
        assertThat(status(call(get("/api/v1/queues/" + s.service()), null, null))).isEqualTo(401);
        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/services"), null, null))).isEqualTo(401);
    }

    @Test
    void aReceptionOperatorIsLimitedToTheirOwnSite() throws Exception {
        Setup mine = setup("SM");
        Setup theirs = setup("ST");
        String reception = token(Role.RECEPTION_OPERATOR, mine.site());
        UUID foreignTicket = UUID.fromString(field(issueOk(token(Role.RECEPTION_OPERATOR, theirs.site()), theirs.service()), "$.id"));

        assertThat(status(issue(reception, UUID.randomUUID().toString(), theirs.service()))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/tickets/" + foreignTicket), reception, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/queues/" + theirs.service()), reception, null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/sites/" + theirs.site() + "/services"), reception, null))).isEqualTo(403);
        assertThat(status(issue(reception, UUID.randomUUID().toString(), mine.service()))).isEqualTo(201);
        assertThat(count("ticket", "site_id = ?", theirs.site())).as("nothing issued for the other site").isEqualTo(1);
    }

    @Test
    void aTeamAdminSeesOnlyTheQueuesOfTheirServiceGroups() throws Exception {
        Setup s = setup("TA");
        UUID otherGroup = newGroup(s.site(), "TB");
        UUID otherService = newService(otherGroup, "TB", "[\"reception\"]", "both", true);
        String teamAdmin = login(Role.TEAM_ADMIN, new UUID[0], new UUID[] {s.group()}).token();

        assertThat(status(call(get("/api/v1/queues/" + s.service()), teamAdmin, null))).isEqualTo(200);
        assertThat(status(call(get("/api/v1/queues/" + otherService), teamAdmin, null))).isEqualTo(403);
    }

    // ---- the ticket table and the catalogue (FR-CFG-015) -------------------------------------------------------

    @Test
    void aServiceThatHasTicketsCanNoLongerBeDeleted() throws Exception {
        Setup s = setup("DL");
        issueOk(token(Role.RECEPTION_OPERATOR, s.site()), s.service());

        MvcResult refused = call(delete("/api/v1/services/" + s.service()), token(Role.ORG_ADMIN), null);

        assertThat(status(refused)).isEqualTo(409);
        assertThat(errorCode(refused)).isEqualTo("conflict");
        assertThat(count("service", "id = ?", s.service())).isEqualTo(1);
    }

    // ---- helpers for concurrency -------------------------------------------------------------------------------

    private List<MvcResult> inParallel(int threads, ThrowingFunction task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                calls.add(() -> task.apply(n));
            }
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : pool.invokeAll(calls)) results.add(future.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction {
        MvcResult apply(int index) throws Exception;
    }
}
