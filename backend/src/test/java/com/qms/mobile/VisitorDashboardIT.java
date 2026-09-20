package com.qms.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A registered visitor's own "my account" read model against real PostgreSQL (ticket 41, FR-MOB-002): active
 * tickets, appointment history and saved sites, scoped strictly to the caller's own visitor id, plus saving and
 * removing a site.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, VisitorDashboardIT.Clocks.class, VisitorAuthTestSupport.Fakes.class})
class VisitorDashboardIT {

    static final Path KEY_DIR = newKeyDir();

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
            return Files.createTempDirectory("qms-keys-visitor-dashboard");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired CapturingVisitorOtpMailer mailer;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.now());
    }

    private String signIn(String email) throws Exception {
        return VisitorAuthTestSupport.mintAccessToken(mvc, mailer, email);
    }

    private UUID newSite(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, ?, ?, 'Asia/Dhaka', 'Road 1', 'en', '[\"en\"]'::jsonb)",
                id, name, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newGroup(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Desk\"}'::jsonb, ?)", id, site, "G" + id.toString().substring(0, 4));
        return id;
    }

    private UUID newService(UUID group) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\"]'::jsonb, true)",
                id, group, "T" + id.toString().substring(0, 4));
        return id;
    }

    /** A minimal ticket row directly, since issuing one for real is out of this suite's scope. */
    private UUID newTicket(UUID visitorId, UUID siteId, UUID groupId, UUID serviceId, String state) {
        UUID visitId = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, now())", visitId, siteId);
        UUID ticketId = UUID.randomUUID();
        String tokenNumber = "T-" + ticketId.toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, visit_id, origin_channel, state, issued_at, queued_at, secret_hash, visitor_id)"
                        + " VALUES (?, ?, 1, 'k', ?, ?, ?, ?, 'reception', ?, now(), now(), 'h', ?)",
                ticketId, tokenNumber, serviceId, groupId, siteId, visitId, state, visitorId);
        return ticketId;
    }

    private UUID newAppointment(UUID visitorId, UUID serviceId, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO appointment (id, reference_code, service_id, visitor_id, slot_date, slot_start, slot_end, state, source, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_DATE, '09:00', '09:30', ?, 'staff', now(), now())",
                id, "A-" + id.toString().substring(0, 8), serviceId, visitorId, state);
        return id;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void showsOnlyTheCallersOwnActiveTicketsExcludingTerminalStates() throws Exception {
        String token = signIn("tickets-" + UUID.randomUUID() + "@example.com");
        UUID visitorId = UUID.fromString(meId(token));
        UUID site = newSite("Main");
        UUID group = newGroup(site);
        UUID service = newService(group);
        newTicket(visitorId, site, group, service, "waiting");
        newTicket(visitorId, site, group, service, "completed"); // terminal: must not show
        UUID otherVisitor = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, email, created_at) VALUES (?, ?, now())", otherVisitor, "other-" + otherVisitor + "@example.com");
        newTicket(otherVisitor, site, group, service, "waiting"); // another visitor's own ticket: must not show either

        MvcResult result = mvc.perform(get("/api/v1/visitors/me/tickets").header("Authorization", "Bearer " + token)).andReturn();

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<String> states = JsonPath.read(body(result), "$.items[*].state");
        assertThat(states).containsExactly("waiting");
    }

    @Test
    void showsTheCallersOwnAppointmentHistoryExcludingHeldSlots() throws Exception {
        String token = signIn("appointments-" + UUID.randomUUID() + "@example.com");
        UUID visitorId = UUID.fromString(meId(token));
        UUID site = newSite("Main2");
        UUID group = newGroup(site);
        UUID service = newService(group);
        newAppointment(visitorId, service, "booked");
        newAppointment(visitorId, service, "cancelled");
        newAppointment(visitorId, service, "held_slot"); // not yet confirmed: must not show

        MvcResult result = mvc.perform(get("/api/v1/visitors/me/appointments").header("Authorization", "Bearer " + token)).andReturn();

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        List<String> states = JsonPath.read(body(result), "$.items[*].state");
        assertThat(states).containsExactlyInAnyOrder("booked", "cancelled");
    }

    @Test
    void aVisitorCanSaveAndRemoveASite() throws Exception {
        String token = signIn("sites-" + UUID.randomUUID() + "@example.com");
        UUID site = newSite("Saved site");

        MvcResult saved = mvc.perform(post("/api/v1/visitors/me/saved-sites/" + site).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(status(saved)).isEqualTo(204);

        MvcResult listed = mvc.perform(get("/api/v1/visitors/me/saved-sites").header("Authorization", "Bearer " + token)).andReturn();
        assertThat((List<String>) JsonPath.read(body(listed), "$.items[*].site_id")).containsExactly(site.toString());

        MvcResult removed = mvc.perform(delete("/api/v1/visitors/me/saved-sites/" + site).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(status(removed)).isEqualTo(204);

        MvcResult afterRemoval = mvc.perform(get("/api/v1/visitors/me/saved-sites").header("Authorization", "Bearer " + token)).andReturn();
        assertThat((List<String>) JsonPath.read(body(afterRemoval), "$.items[*].site_id")).isEmpty();
    }

    @Test
    void savingAnUnknownSiteIsNotFound() throws Exception {
        String token = signIn("bad-site-" + UUID.randomUUID() + "@example.com");

        MvcResult result = mvc.perform(post("/api/v1/visitors/me/saved-sites/" + UUID.randomUUID()).header("Authorization", "Bearer " + token)).andReturn();

        assertThat(status(result)).isEqualTo(404);
    }

    @Test
    void anAnonymousCallerCannotReadTheDashboard() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/visitors/me/tickets")).andReturn();
        assertThat(status(result)).isEqualTo(401);
    }

    private String meId(String accessToken) throws Exception {
        MvcResult me = mvc.perform(get("/api/v1/auth/visitor/me").header("Authorization", "Bearer " + accessToken)).andReturn();
        return JsonPath.read(body(me), "$.id");
    }
}
