package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.mobile.CapturingVisitorOtpMailer;
import com.qms.mobile.VisitorAuthTestSupport;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A registered visitor's own self-service booking, reschedule and cancel against real PostgreSQL (ticket 41,
 * FR-APT-020, §5.2 "Book an appointment" — S for Visitor). {@code AppointmentBookingIT} already covers the staff
 * path end to end; this file is only what changes once the caller is a visitor: the source and visitor id are the
 * server's own choice, never the client's; a visitor can act only on their own appointment; and past the cut-off a
 * visitor is refused outright, with no reason able to lift it (unlike staff, already covered by {@code
 * AppointmentBookingIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, VisitorAppointmentSelfServiceIT.Clocks.class, VisitorAuthTestSupport.Fakes.class})
class VisitorAppointmentSelfServiceIT {

    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");
    static final String MONDAY = "2026-09-21";

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
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-appointments");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired CapturingVisitorOtpMailer mailer;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    private record Setup(UUID site, UUID service) {}

    private Setup setup(String prefix) throws Exception {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main', ?, 'Asia/Dhaka', '1 Road', 'en', '[\"en\"]'::jsonb)",
                site, "S-" + prefix + "-" + site.toString().substring(0, 6));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, ?)", group, site, "G" + prefix);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\"]'::jsonb, 'both', true)",
                service, group, "T" + prefix);

        String admin = adminToken(site);
        putServiceTemplate(admin, service, 1, "09:00", "12:00", 30, 5);
        return new Setup(site, service);
    }

    private String adminToken(UUID site) throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, 'Admin', 'en')",
                user, "admin-" + user, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("Correct-Horse-9"));
        jdbc.update(
                connection -> {
                    var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, 'org_admin', ?, ?)");
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, user);
                    ps.setArray(3, connection.createArrayOf("uuid", new UUID[] {site}));
                    ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
                    return ps;
                });
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"Correct-Horse-9\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return JsonPath.read(body(result), "$.access_token");
        } finally {
            clock.set(testTime);
        }
    }

    private String visitorToken(String email) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            return VisitorAuthTestSupport.mintAccessToken(mvc, mailer, email);
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        String template = String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, template);
    }

    private MvcResult book(String token, String body) throws Exception {
        return call(post("/api/v1/appointments"), token, body);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    // ---- FR-APT-020, §5.2: a visitor books, reschedules and cancels only their own appointment ---------------------

    @Test
    void aVisitorCanBookTheirOwnAppointmentWithoutSupplyingVisitorIdOrSource() throws Exception {
        Setup s = setup("BK");
        String visitor = visitorToken("book-own-" + UUID.randomUUID() + "@example.com");

        MvcResult booked = book(visitor, String.format("{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\"}", s.service(), MONDAY));

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        assertThat((String) field(booked, "$.source")).isEqualTo("visitor");
        assertThat((String) field(booked, "$.state")).isEqualTo("booked");
        String ownId = meId(visitor);
        assertThat((String) field(booked, "$.visitor_id")).isEqualTo(ownId);
    }

    @Test
    void aVisitorCannotClaimAnotherVisitorIdOrAStaffSourceOrChooseAPriorityClass() throws Exception {
        Setup s = setup("SPOOF");
        String visitor = visitorToken("spoof-" + UUID.randomUUID() + "@example.com");
        String ownId = meId(visitor);
        UUID someoneElse = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, email, created_at) VALUES (?, ?, now())", someoneElse, "someone-else-" + someoneElse + "@example.com");

        MvcResult booked = book(
                visitor,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"staff\",\"visitor_id\":\"%s\",\"priority_class_id\":\"%s\"}",
                        s.service(), MONDAY, someoneElse, UUID.randomUUID()));

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        assertThat((String) field(booked, "$.visitor_id")).isEqualTo(ownId);
        assertThat((String) field(booked, "$.source")).isEqualTo("visitor");
        assertThat((Object) field(booked, "$.priority_class_id")).isNull();
    }

    @Test
    void aVisitorCanRescheduleAndCancelTheirOwnAppointmentBeforeTheCutoff() throws Exception {
        Setup s = setup("OWN");
        String visitor = visitorToken("own-actions-" + UUID.randomUUID() + "@example.com");
        MvcResult booked = book(visitor, String.format("{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\"}", s.service(), MONDAY));
        UUID id = UUID.fromString(field(booked, "$.id"));

        MvcResult rescheduled = call(patch("/api/v1/appointments/" + id), visitor, String.format("{\"date\":\"%s\",\"start\":\"09:30\",\"end\":\"10:00\"}", MONDAY));
        assertThat(status(rescheduled)).as(body(rescheduled)).isEqualTo(200);
        assertThat((String) field(rescheduled, "$.start")).isEqualTo("09:30");

        MvcResult cancelled = call(delete("/api/v1/appointments/" + id), visitor, null);
        assertThat(status(cancelled)).isEqualTo(204);
    }

    @Test
    void aVisitorCannotRescheduleOrCancelAnotherVisitorsAppointment() throws Exception {
        Setup s = setup("OTHER");
        String owner = visitorToken("owner-" + UUID.randomUUID() + "@example.com");
        String stranger = visitorToken("stranger-" + UUID.randomUUID() + "@example.com");
        MvcResult booked = book(owner, String.format("{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\"}", s.service(), MONDAY));
        UUID id = UUID.fromString(field(booked, "$.id"));

        MvcResult rescheduled = call(patch("/api/v1/appointments/" + id), stranger, String.format("{\"date\":\"%s\",\"start\":\"09:30\",\"end\":\"10:00\"}", MONDAY));
        assertThat(status(rescheduled)).isEqualTo(403);

        MvcResult cancelled = call(delete("/api/v1/appointments/" + id), stranger, null);
        assertThat(status(cancelled)).isEqualTo(403);
    }

    @Test
    void pastTheCutoffAVisitorIsRefusedEvenWithAReasonButStaffMayStillAct() throws Exception {
        Setup s = setup("CUTOFF");
        String visitor = visitorToken("cutoff-" + UUID.randomUUID() + "@example.com");
        MvcResult booked = book(visitor, String.format("{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\"}", s.service(), MONDAY));
        UUID id = UUID.fromString(field(booked, "$.id"));

        // 09:00 Dhaka (UTC+6) on Monday is 03:00Z; the default cutoff is 120 minutes before that (01:00Z). 02:50Z is
        // well past it — 10 minutes before the slot itself.
        clock.set(Instant.parse("2026-09-21T02:50:00Z"));

        MvcResult refused = call(delete("/api/v1/appointments/" + id), visitor, "{\"reason\":\"please let me anyway\"}");
        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("visitor_cutoff_passed");

        String admin = adminToken(s.site());
        MvcResult staffCancelled = call(delete("/api/v1/appointments/" + id), admin, "{\"reason\":\"visitor called in\"}");
        assertThat(status(staffCancelled)).as(body(staffCancelled)).isEqualTo(204);
    }

    private String meId(String accessToken) throws Exception {
        MvcResult me = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/visitor/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn();
        return field(me, "$.id");
    }
}
