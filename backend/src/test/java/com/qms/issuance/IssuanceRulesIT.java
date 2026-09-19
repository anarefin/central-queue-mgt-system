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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Ticket 21 against real PostgreSQL with a clock the test moves: issuance refuses, with a specific, localised reason,
 * outside business hours or on a holiday, past a channel's cut-off, once a Service's daily cap is hit, with no Agent
 * rostered, for a duplicate ticket under a Service's policy, once an actor is rate-limited, and while the system is in
 * maintenance mode (FR-CFG-020..023, FR-ISS-003, FR-ISS-004, API-090, FR-OPS-043).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, IssuanceRulesIT.Clocks.class})
class IssuanceRulesIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6): office hours, a weekday in the office week below. */
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");

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
            return Files.createTempDirectory("qms-keys-issuance-rules");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
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

    private record Setup(UUID site, UUID zone, UUID group, UUID service) {}

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newZone(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, building_label, floor_label) VALUES (?, ?, 'Ground waiting', 'Block A', 'Ground')", id, site);
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

    private UUID newService(UUID group, String prefix, String channelsJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, ?::jsonb, 'both', true)",
                id, group, prefix, channelsJson);
        return id;
    }

    private void link(UUID counter, UUID service) {
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
    }

    /** A site with one zone, a counter, a group and one service, plus an Agent rostered on its team. */
    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID zone = newZone(site);
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix, "[\"reception\",\"kiosk\",\"mobile\"]");
        link(newCounter(zone, "1"), service);
        roster(group, agent(site));
        return new Setup(site, zone, group, service);
    }

    private UUID agent(UUID site) {
        UUID user = createUser("agent");
        jdbc.update(
                connection -> {
                    var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, 'agent', ?, ?)");
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, user);
                    ps.setArray(3, connection.createArrayOf("uuid", new UUID[] {site}));
                    ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
                    return ps;
                });
        return user;
    }

    private void roster(UUID group, UUID user) {
        UUID team = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Desk')", team, group);
        jdbc.update("INSERT INTO team_member (team_id, user_id) VALUES (?, ?)", team, user);
    }

    private UUID visitor() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, 'V', 'general', now())", id, "V-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    /** Minted at real time whatever the test clock says: tokens are validated against the system clock, not this bean. */
    private String token(Role role, UUID... sites) throws Exception {
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

    private MvcResult issue(String token, UUID service) throws Exception {
        return issue(token, service, "reception", null, false);
    }

    private MvcResult issue(String token, UUID service, String channel, UUID visitorId, boolean confirm) throws Exception {
        StringBuilder body = new StringBuilder("{\"service_id\":\"" + service + "\",\"origin_channel\":\"" + channel + "\"");
        if (visitorId != null) body.append(",\"visitor_id\":\"").append(visitorId).append('"');
        if (confirm) body.append(",\"confirm_duplicate\":true");
        body.append('}');
        return call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), token, body.toString());
    }

    private MvcResult issueOk(String token, UUID service) throws Exception {
        MvcResult result = issue(token, service);
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

    private String reasonOf(MvcResult refused, String code) throws Exception {
        assertThat(status(refused)).as(body(refused)).isIn(409, 429);
        assertThat((String) field(refused, "$.error.code")).isEqualTo(code);
        return field(refused, "$.error.details.reason");
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    // ---- weekly hours and holidays (FR-CFG-020, FR-CFG-021) -----------------------------------------------------

    @Test
    void outsideTheSitesWeeklyHoursIssuanceIsRefusedWithAServiceClosedReason() throws Exception {
        Setup s = setup("HR");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult set = call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":6,\"open\":\"09:00\",\"close\":\"17:00\"}]}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        clock.set(Instant.parse("2026-09-19T18:00:00Z")); // 2026-09-20 00:00 Dhaka: Sunday, not configured.
        assertThat(reasonOf(issue(reception, s.service()), "service_closed")).isEqualTo("outside_hours");
        assertThat(count("ticket", "service_id = ?", s.service())).isZero();

        clock.set(BASE); // Saturday 10:00 Dhaka, within the configured window.
        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
    }

    @Test
    void aServicesOwnHoursOverrideItsSitesForThatServiceOnly() throws Exception {
        Setup s = setup("SO");
        UUID other = newService(s.group(), "SP", "[\"reception\"]");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":6,\"open\":\"09:00\",\"close\":\"17:00\"}]}");
        call(put("/api/v1/services/" + s.service() + "/hours"), admin, "{\"days\":[{\"weekday\":6,\"open\":\"00:00\",\"close\":\"23:59\"}]}");

        clock.set(Instant.parse("2026-09-19T18:00:00Z")); // Sunday 00:00 Dhaka: closed at the Site and by the override.
        assertThat(reasonOf(issue(reception, s.service()), "service_closed")).isEqualTo("outside_hours");
        assertThat(reasonOf(issue(reception, other), "service_closed")).isEqualTo("outside_hours");

        MvcResult clear = call(put("/api/v1/services/" + s.service() + "/hours"), admin, "{\"days\":[]}");
        assertThat(status(clear)).isEqualTo(200);
        assertThat(reasonOf(issue(reception, s.service()), "service_closed")).as("with no override it follows the Site again").isEqualTo("outside_hours");
    }

    @Test
    void aHolidayClosesTheSiteAndAHalfDayClosesEarly() throws Exception {
        Setup s = setup("HD");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        // Saturday 19 Sept 2026 is a full holiday.
        MvcResult created = call(post("/api/v1/sites/" + s.site() + "/holidays"), admin, "{\"date\":\"2026-09-19\",\"name\":\"Founders Day\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);

        assertThat(reasonOf(issue(reception, s.service()), "service_closed")).isEqualTo("holiday");
        assertThat((String) field(issue(reception, s.service()), "$.error.details.holiday")).isEqualTo("Founders Day");

        MvcResult listed = call(get("/api/v1/sites/" + s.site() + "/holidays"), admin, null);
        assertThat((List<String>) field(listed, "$.items[*].name")).containsExactly("Founders Day");
        UUID holidayId = UUID.fromString(field(created, "$.id"));
        assertThat(status(call(delete("/api/v1/sites/" + s.site() + "/holidays/" + holidayId), admin, null))).isEqualTo(204);
        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);

        // A half-day closes early instead of all day: it is still open right up to its own closing time...
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":6,\"open\":\"09:00\",\"close\":\"17:00\"}]}");
        call(post("/api/v1/sites/" + s.site() + "/holidays"), admin, "{\"date\":\"2026-09-19\",\"name\":\"Eve\",\"half_day\":true,\"close_time\":\"11:00\"}");
        clock.set(Instant.parse("2026-09-19T04:30:00Z")); // 10:30 Dhaka, before the half-day's earlier close.
        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
        // ...and closed like any other day once past it, not "holiday" (the day is only a holiday outright when it has no close time).
        clock.set(Instant.parse("2026-09-19T05:30:00Z")); // 11:30 Dhaka, after the half-day close but before the usual one.
        assertThat(reasonOf(issue(reception, s.service()), "service_closed")).isEqualTo("outside_hours");
    }

    // ---- channel cut-off (FR-CFG-022) ---------------------------------------------------------------------------

    @Test
    void aChannelStopsIssuingItsOwnMinutesBeforeClosingAndTheReasonNamesTheCutoff() throws Exception {
        // POST /tickets issues at reception only (staff channel); a kiosk device drives IssuanceService directly, the
        // way the kiosk channel itself will once it exists (ticket 25), to show the cut-off is per channel.
        Setup s = setup("CO");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":6,\"open\":\"09:00\",\"close\":\"17:00\"}]}");
        MvcResult set = call(put("/api/v1/sites/" + s.site() + "/issuance-cutoffs"), admin, "{\"minutes_before_close\":{\"kiosk\":60}}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        clock.set(Instant.parse("2026-09-19T10:30:00Z")); // 16:30 Dhaka: within the kiosk cut-off, before the reception one (none set).
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> issuanceService.issue(new IssueCommand(s.service(), Channels.KIOSK, UUID.randomUUID(), ActorType.DEVICE, null)))
                .isInstanceOf(com.qms.platform.ApiException.class)
                .satisfies(e -> {
                    var apiEx = (com.qms.platform.ApiException) e;
                    assertThat(apiEx.code().wire()).isEqualTo("service_closed");
                    assertThat(apiEx.details()).containsEntry("reason", "past_cutoff");
                });
        assertThat(status(issueOk(reception, s.service()))).as("reception has no cut-off configured").isEqualTo(201);

        MvcResult read = call(get("/api/v1/sites/" + s.site() + "/issuance-cutoffs"), admin, null);
        assertThat((Integer) field(read, "$.minutes_before_close.kiosk")).isEqualTo(60);
    }

    // ---- daily cap (FR-CFG-023) ---------------------------------------------------------------------------------

    @Test
    void aDailyCapRefusesOnceReachedWithAConfiguredMessageInTheCallersLanguage() throws Exception {
        Setup s = setup("CP");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult set = call(
                put("/api/v1/services/" + s.service() + "/issuance-rule"),
                admin,
                "{\"daily_cap\":1,\"cap_message_i18n\":{\"en\":\"No more tokens today\",\"bn\":\"আজকের টোকেন শেষ\"}}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
        MvcResult refused = issue(reception, s.service());
        assertThat(reasonOf(refused, "service_closed")).isEqualTo("cap_reached");
        assertThat((Integer) field(refused, "$.error.details.daily_cap")).isEqualTo(1);
        assertThat((String) field(refused, "$.error.message")).isEqualTo("No more tokens today");

        MvcResult refusedBn = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()).header("Accept-Language", "bn"),
                reception, "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\"}");
        assertThat((String) field(refusedBn, "$.error.message")).isEqualTo("আজকের টোকেন শেষ");
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(1);
    }

    // ---- no agent rostered (FR-ISS-003) -------------------------------------------------------------------------

    @Test
    void aServiceRequiringARosteredAgentRefusesWithNoneOnItsTeam() throws Exception {
        Setup s = setup("NA");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"require_agent\":true}");
        assertThat(status(issueOk(reception, s.service()))).as("rostered from setup()").isEqualTo(201);

        jdbc.update("DELETE FROM team_member WHERE team_id IN (SELECT id FROM team WHERE service_group_id = ?)", s.group());

        assertThat(reasonOf(issue(reception, s.service()), "conflict")).isEqualTo("no_agent_rostered");
    }

    // ---- duplicate policy (FR-ISS-004) --------------------------------------------------------------------------

    @Test
    void duplicatePolicyAllowsWarnsOrBlocksASecondActiveTicketForTheSameVisitor() throws Exception {
        Setup s = setup("DP");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID visitor = visitor();

        assertThat(status(issue(reception, s.service(), "reception", visitor, false))).as("allow is the default").isEqualTo(201);
        assertThat(status(issue(reception, s.service(), "reception", visitor, false))).isEqualTo(201);

        call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"duplicate_policy\":\"warn\"}");
        UUID warned = visitor();
        assertThat(status(issue(reception, s.service(), "reception", warned, false))).isEqualTo(201);
        MvcResult warning = issue(reception, s.service(), "reception", warned, false);
        assertThat(reasonOf(warning, "conflict")).isEqualTo("duplicate_ticket");
        assertThat((String) field(warning, "$.error.details.policy")).isEqualTo("warn");
        assertThat(status(issue(reception, s.service(), "reception", warned, true))).as("confirmed, it issues anyway").isEqualTo(201);

        call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"duplicate_policy\":\"block\"}");
        UUID blocked = visitor();
        assertThat(status(issue(reception, s.service(), "reception", blocked, false))).isEqualTo(201);
        assertThat(reasonOf(issue(reception, s.service(), "reception", blocked, true), "conflict")).as("block cannot be confirmed through").isEqualTo("duplicate_ticket");
    }

    @Test
    void anUnknownVisitorIdIsValidationFailed() throws Exception {
        Setup s = setup("UV");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult refused = issue(reception, s.service(), "reception", UUID.randomUUID(), false);

        assertThat(status(refused)).isEqualTo(400);
        assertThat((String) field(refused, "$.error.code")).isEqualTo("validation_failed");
        assertThat((String) field(refused, "$.error.details.fields[0].field")).isEqualTo("visitor_id");
    }

    // ---- rate limits (API-090) -----------------------------------------------------------------------------------
    // POST /tickets only ever issues as a staff actor, who is never rate-limited (§8.5); a device or visitor actor
    // arrives with the kiosk, mobile and appointment-check-in channels (tickets 25+), so the limit itself is driven
    // straight through IssuanceService, the one path every channel will share.

    @Autowired private IssuanceService issuanceService;

    @Test
    void aDeviceOverThePerMinuteLimitIsRefusedThenAllowedOnceTheWindowAges() throws Exception {
        Setup s = setup("RD");
        String admin = token(Role.SYSTEM_ADMIN);
        MvcResult set = call(put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":false,\"visitor_limit_per_hour\":5,\"device_limit_per_minute\":2}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
        UUID device = UUID.randomUUID();

        issuanceService.issue(new IssueCommand(s.service(), Channels.KIOSK, device, ActorType.DEVICE, null));
        issuanceService.issue(new IssueCommand(s.service(), Channels.KIOSK, device, ActorType.DEVICE, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> issuanceService.issue(new IssueCommand(s.service(), Channels.KIOSK, device, ActorType.DEVICE, null)))
                .isInstanceOf(com.qms.platform.ApiException.class)
                .satisfies(e -> {
                    var apiEx = (com.qms.platform.ApiException) e;
                    assertThat(apiEx.code().wire()).isEqualTo("rate_limited");
                    assertThat(apiEx.details()).containsEntry("limit", 2).containsEntry("window_seconds", 60L).containsKey("retry_after_seconds");
                });
        assertThat(count("ticket", "service_id = ?", s.service())).as("the refused attempt issued nothing").isEqualTo(2);

        clock.advance(java.time.Duration.ofMinutes(1).plusSeconds(1));
        assertThat(issuanceService.issue(new IssueCommand(s.service(), Channels.KIOSK, device, ActorType.DEVICE, null))).isNotNull();
    }

    @Test
    void aVisitorOverTheirHourlyLimitIsRefusedThenAllowedOnceTheWindowAges() throws Exception {
        Setup s = setup("RV");
        String admin = token(Role.SYSTEM_ADMIN);
        MvcResult set = call(put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":false,\"visitor_limit_per_hour\":2,\"device_limit_per_minute\":30}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
        UUID visitorActor = UUID.randomUUID();

        issuanceService.issue(new IssueCommand(s.service(), Channels.MOBILE, visitorActor, ActorType.VISITOR, null));
        issuanceService.issue(new IssueCommand(s.service(), Channels.MOBILE, visitorActor, ActorType.VISITOR, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> issuanceService.issue(new IssueCommand(s.service(), Channels.MOBILE, visitorActor, ActorType.VISITOR, null)))
                .isInstanceOf(com.qms.platform.ApiException.class)
                .satisfies(e -> assertThat(((com.qms.platform.ApiException) e).code().wire()).isEqualTo("rate_limited"));

        clock.advance(java.time.Duration.ofHours(1).plusSeconds(1));
        assertThat(issuanceService.issue(new IssueCommand(s.service(), Channels.MOBILE, visitorActor, ActorType.VISITOR, null))).isNotNull();
    }

    @Test
    void aStaffOrSystemActorIsNeverRateLimited() throws Exception {
        Setup s = setup("RS");
        String admin = token(Role.SYSTEM_ADMIN);
        MvcResult set = call(put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":false,\"visitor_limit_per_hour\":1,\"device_limit_per_minute\":1}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
        assertThat(issuanceService.issue(new IssueCommand(s.service(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null))).isNotNull();
    }

    @Test
    void deviceAndVisitorLimitsDefaultTo30PerMinuteAnd5PerHourWhenNeverConfigured() throws Exception {
        Setup s = setup("RF");
        MvcResult read = call(get("/api/v1/issuance-settings"), token(Role.SYSTEM_ADMIN), null);
        assertThat(status(read)).as(body(read)).isEqualTo(200);
        assertThat((Integer) field(read, "$.device_limit_per_minute")).isEqualTo(30);
        assertThat((Integer) field(read, "$.visitor_limit_per_hour")).isEqualTo(5);
    }

    // ---- maintenance mode (FR-OPS-043) --------------------------------------------------------------------------

    @Test
    void maintenanceModeStopsNewIssuanceWithAConfiguredMessageWhileQueuedTicketsAreUnaffected() throws Exception {
        Setup s = setup("MT");
        String admin = token(Role.SYSTEM_ADMIN);
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID queued = UUID.fromString(field(issueOk(reception, s.service()), "$.id"));

        MvcResult set = call(
                put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":true,\"maintenance_message_i18n\":{\"en\":\"Paused for maintenance\"}}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        MvcResult refused = issue(reception, s.service());
        assertThat(status(refused)).isEqualTo(503);
        assertThat((String) field(refused, "$.error.code")).isEqualTo("unavailable");
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("maintenance");
        assertThat((String) field(refused, "$.error.message")).isEqualTo("Paused for maintenance");

        assertThat(status(call(get("/api/v1/tickets/" + queued), reception, null))).as("a ticket already issued is unaffected").isEqualTo(200);

        call(put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":false}");
        assertThat(status(issueOk(reception, s.service()))).isEqualTo(201);
    }

    // ---- permissions, scope and audit (DoD, SRS §27.5) ----------------------------------------------------------

    @Test
    void theIssuanceRuleEndpointsArePermissionCheckedAndScopedToTheCallersSites() throws Exception {
        Setup s = setup("PM");
        Setup theirs = setup("PT");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        String orgAdminOther = token(Role.ORG_ADMIN, theirs.site());
        String orgAdminMine = token(Role.ORG_ADMIN, s.site());

        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/hours"), reception, null))).as("wrong permission").isEqualTo(403);
        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/hours"), orgAdminOther, null))).as("outside caller's sites").isEqualTo(403);
        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/hours"), orgAdminMine, null))).isEqualTo(200);
        assertThat(status(call(get("/api/v1/sites/" + s.site() + "/hours"), null, null))).isEqualTo(401);
        assertThat(status(call(get("/api/v1/issuance-settings"), orgAdminMine, null))).as("scoped admin, not organisation-wide").isEqualTo(403);
    }

    @Test
    void changingAServicesIssuanceRuleWritesAnAuditEntryWithBeforeAndAfterAndNoOpsWriteNothing() throws Exception {
        Setup s = setup("AU");
        String admin = token(Role.ORG_ADMIN, s.site());

        call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"daily_cap\":5}");
        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT before::text AS before, after::text AS after FROM audit_log WHERE action = 'service_issuance_rule.updated' AND entity_id = ?", s.service());
        assertThat((String) entry.get("before")).containsPattern("\"daily_cap\"\\s*:\\s*null");
        assertThat((String) entry.get("after")).containsPattern("\"daily_cap\"\\s*:\\s*5");

        int before = count("audit_log", "action = 'service_issuance_rule.updated' AND entity_id = ?", s.service());
        call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"daily_cap\":5}");
        assertThat(count("audit_log", "action = 'service_issuance_rule.updated' AND entity_id = ?", s.service())).as("changing nothing writes nothing").isEqualTo(before);
    }

    @Test
    void changingHoursOrCutoffsAlsoWritesAnAuditEntry() throws Exception {
        Setup s = setup("AH");
        String admin = token(Role.ORG_ADMIN, s.site());

        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":6,\"open\":\"09:00\",\"close\":\"17:00\"}]}");
        assertThat(count("audit_log", "action = 'business_hours.updated' AND entity_id = ?", s.site())).isEqualTo(1);

        call(put("/api/v1/sites/" + s.site() + "/issuance-cutoffs"), admin, "{\"minutes_before_close\":{\"kiosk\":30}}");
        assertThat(count("audit_log", "action = 'channel_cutoff.updated' AND entity_id = ?", s.site())).isEqualTo(1);
    }

    @Test
    void enablingAndDisablingMaintenanceAreDistinctAuditActions() throws Exception {
        Setup s = setup("MA");
        String admin = token(Role.SYSTEM_ADMIN);

        call(put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":true}");
        call(put("/api/v1/issuance-settings"), admin, "{\"maintenance_enabled\":false}");

        assertThat(count("audit_log", "action = 'issuance.maintenance_enabled'")).isEqualTo(1);
        assertThat(count("audit_log", "action = 'issuance.maintenance_disabled'")).isEqualTo(1);
    }

    // ---- validation --------------------------------------------------------------------------------------------

    @Test
    void malformedHoursCutoffsAndCapsAreValidationFailedNamingTheField() throws Exception {
        Setup s = setup("VF");
        String admin = token(Role.ORG_ADMIN, s.site());

        assertThat((String) field(call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":9,\"open\":\"09:00\",\"close\":\"17:00\"}]}"), "$.error.details.fields[0].field"))
                .isEqualTo("days");
        assertThat((String) field(call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":1,\"open\":\"17:00\",\"close\":\"09:00\"}]}"), "$.error.details.fields[0].field"))
                .isEqualTo("days");
        assertThat((String) field(call(put("/api/v1/sites/" + s.site() + "/issuance-cutoffs"), admin, "{\"minutes_before_close\":{\"fax\":10}}"), "$.error.details.fields[0].field"))
                .isEqualTo("minutes_before_close");
        assertThat((String) field(call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"daily_cap\":0}"), "$.error.details.fields[0].field"))
                .isEqualTo("daily_cap");
        assertThat((String) field(call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"duplicate_policy\":\"maybe\"}"), "$.error.details.fields[0].field"))
                .isEqualTo("duplicate_policy");
        MvcResult duplicateHoliday1 = call(post("/api/v1/sites/" + s.site() + "/holidays"), admin, "{\"date\":\"2026-12-25\",\"name\":\"A\"}");
        assertThat(status(duplicateHoliday1)).isEqualTo(201);
        MvcResult duplicateHoliday2 = call(post("/api/v1/sites/" + s.site() + "/holidays"), admin, "{\"date\":\"2026-12-25\",\"name\":\"B\"}");
        assertThat(status(duplicateHoliday2)).isEqualTo(409);
        assertThat((String) field(duplicateHoliday2, "$.error.details.reason")).isEqualTo("holiday_exists");
    }

    // ---- concurrency: the cap is enforced under contention -----------------------------------------------------

    @Test
    void theCapIsEnforcedUnderConcurrentIssuanceWithNoOverrun() throws Exception {
        Setup s = setup("CC");
        String admin = token(Role.ORG_ADMIN, s.site());
        call(put("/api/v1/services/" + s.service() + "/issuance-rule"), admin, "{\"daily_cap\":5}");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        List<MvcResult> results = inParallel(10, i -> issue(reception, s.service()));

        long issued = results.stream().filter(r -> status(r) == 201).count();
        assertThat(issued).isEqualTo(5);
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(5);
    }

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
