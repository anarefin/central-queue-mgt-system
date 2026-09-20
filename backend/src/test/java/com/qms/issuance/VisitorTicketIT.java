package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * The visitor ticket page's own REST surface end to end (ticket 37, §20.2, §20.4, FR-MOB-013, FR-MOB-030, FR-SEC-033):
 * anonymous, by ticket id plus its own secret, read-only except for cancelling before being called.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class VisitorTicketIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-ticket");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID zone, UUID counter, UUID group, UUID service) {}

    private Setup setup() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, building_label, floor_label) VALUES (?, ?, 'Ground waiting', 'Block A', 'Ground')", zone, site);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", counter, zone);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both', true)",
                service, group);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
        return new Setup(site, zone, counter, group, service);
    }

    private String staffToken() throws Exception {
        UUID user = UUID.randomUUID();
        String username = "reception-" + user;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), "reception", "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, Role.RECEPTION_OPERATOR.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(login)).as(body(login)).isEqualTo(200);
        return field(login, "$.access_token");
    }

    private record IssuedTicket(UUID id, String secret, String tokenNumber) {}

    private IssuedTicket issue(String staffToken, UUID service) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + staffToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + service + "\",\"origin_channel\":\"reception\"}"))
                .andReturn();
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return new IssuedTicket(UUID.fromString(field(result, "$.id")), field(result, "$.secret"), field(result, "$.token_number"));
    }

    private MvcResult visitorView(UUID id, String secret) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/tickets/" + id + "/visitor");
        if (secret != null) request.header("X-Ticket-Secret", secret);
        return mvc.perform(request).andReturn();
    }

    private MvcResult visitorCancel(UUID id, String secret) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tickets/" + id + "/visitor-cancel");
        if (secret != null) request.header("X-Ticket-Secret", secret);
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

    // ---- reads (FR-MOB-013, FR-MOB-032, FR-SEC-033) -------------------------------------------------------------

    @Test
    void aVisitorReadsTheirOwnTicketWithTheCorrectSecret() throws Exception {
        Setup s = setup();
        IssuedTicket ticket = issue(staffToken(), s.service());

        MvcResult view = visitorView(ticket.id(), ticket.secret());
        assertThat(status(view)).as(body(view)).isEqualTo(200);
        assertThat((String) field(view, "$.ticket_id")).isEqualTo(ticket.id().toString());
        assertThat((String) field(view, "$.token_number")).isEqualTo(ticket.tokenNumber());
        assertThat((String) field(view, "$.state")).isEqualTo("waiting");
        assertThat((Integer) field(view, "$.position")).isEqualTo(1);
        assertThat((Integer) field(view, "$.estimated_wait_minutes.low")).isNotNull();
        assertThat((String) field(view, "$.zone.floor_label")).isEqualTo("Ground");
        assertThat((String) field(view, "$.zone.building_label")).isEqualTo("Block A");
        assertThat((Object) field(view, "$.zone.wayfinding_image_url")).isNull();
        assertThat((Object) field(view, "$.now_serving_token_number")).isNull();
        assertThat((String) field(view, "$.updated_at")).isNotBlank();
    }

    @Test
    void aWrongOrMissingSecretOrAnUnknownTicketIsUnauthenticatedNeverNotFound() throws Exception {
        Setup s = setup();
        IssuedTicket ticket = issue(staffToken(), s.service());

        MvcResult wrong = visitorView(ticket.id(), "not-the-right-secret");
        assertThat(status(wrong)).isEqualTo(401);
        assertThat((String) field(wrong, "$.error.code")).isEqualTo("unauthenticated");

        MvcResult missing = visitorView(ticket.id(), null);
        assertThat(status(missing)).isEqualTo(401);

        // A token number alone (a correct-shaped secret for a ticket that does not exist) reveals nothing either (FR-SEC-033).
        MvcResult unknown = visitorView(UUID.randomUUID(), ticket.secret());
        assertThat(status(unknown)).isEqualTo(401);
    }

    @Test
    void theWayfindingImageAppearsOnceTheZoneHasOne() throws Exception {
        Setup s = setup();
        jdbc.update("UPDATE zone SET wayfinding_image_url = ? WHERE id = ?", "https://cdn.example.org/zone-a.png", s.zone());
        IssuedTicket ticket = issue(staffToken(), s.service());

        MvcResult view = visitorView(ticket.id(), ticket.secret());
        assertThat((String) field(view, "$.zone.wayfinding_image_url")).isEqualTo("https://cdn.example.org/zone-a.png");
    }

    // ---- cancel (FR-MOB-030) -------------------------------------------------------------------------------------

    @Test
    void aVisitorCancelsTheirOwnWaitingTicketButNotTwice() throws Exception {
        Setup s = setup();
        IssuedTicket ticket = issue(staffToken(), s.service());

        MvcResult cancelled = visitorCancel(ticket.id(), ticket.secret());
        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(200);
        assertThat((String) field(cancelled, "$.state")).isEqualTo("cancelled");
        assertThat((String) jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, ticket.id())).isEqualTo("cancelled");

        MvcResult again = visitorCancel(ticket.id(), ticket.secret());
        assertThat(status(again)).isEqualTo(409);
        assertThat((String) field(again, "$.error.details.reason")).isEqualTo("ticket_already_called");
    }

    @Test
    void aTicketAlreadyCalledCanNoLongerBeCancelledByTheVisitor() throws Exception {
        Setup s = setup();
        IssuedTicket ticket = issue(staffToken(), s.service());
        jdbc.update("UPDATE ticket SET state = 'called' WHERE id = ?", ticket.id());

        MvcResult refused = visitorCancel(ticket.id(), ticket.secret());
        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("ticket_already_called");
        assertThat((String) jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, ticket.id())).isEqualTo("called");
    }
}
