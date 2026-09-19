package com.qms.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Device pairing and fleet management over HTTP against real PostgreSQL (ticket 24): the pairing exchange, silent
 * refresh with reuse detection, heartbeat, bootstrap, the central health view, revoke, and the permission and
 * validation rules around them (FR-OPS-011, FR-OPS-041, NFR-SEC-005, API-011, API-017).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, DeviceFleetIT.Clocks.class})
class DeviceFleetIT {

    static final String PASSWORD = "Correct-Horse-9";
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
            return Files.createTempDirectory("qms-keys-device");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    // ---- helpers -----------------------------------------------------------------------------------------------

    private String tokenFor(Role role, UUID... sites) throws Exception {
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
            ps.setArray(4, connection.createArrayOf("uuid", sites));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.access_token");
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

    private static String errorCode(MvcResult result) throws Exception {
        return field(result, "$.error.code");
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID createSite(String token) throws Exception {
        String json = "{\"name\":\"Main campus\",\"code\":\"" + unique("S") + "\",\"timezone\":\"Asia/Dhaka\",\"address\":\"1 Campus Road\","
                + "\"default_language\":\"bn\",\"enabled_languages\":[\"bn\",\"en\"]}";
        MvcResult created = call(post("/api/v1/sites"), token, json);
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private UUID createZone(String token, UUID site) throws Exception {
        MvcResult created = call(post("/api/v1/sites/" + site + "/zones"), token,
                "{\"name\":\"Ground waiting\",\"floor_label\":\"Ground\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private String pairingCode(String token, String kind, UUID site, UUID zone) throws Exception {
        String zoneJson = zone == null ? "null" : "\"" + zone + "\"";
        MvcResult created = call(post("/api/v1/devices/pairing-codes"), token,
                "{\"kind\":\"" + kind + "\",\"site_id\":\"" + site + "\",\"zone_id\":" + zoneJson + ",\"label\":\"Front desk\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return field(created, "$.code");
    }

    private MvcResult pair(String code) throws Exception {
        return call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + code + "\"}");
    }

    // ---- pairing (FR-OPS-011) ------------------------------------------------------------------------------------

    @Test
    void aKioskPairsWithItsCodeAndGetsAnAccessAndRefreshToken() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        String code = pairingCode(admin, "kiosk", site, null);

        MvcResult paired = pair(code);

        assertThat(status(paired)).as(body(paired)).isEqualTo(201);
        assertThat((String) field(paired, "$.kind")).isEqualTo("kiosk");
        assertThat((String) field(paired, "$.site_id")).isEqualTo(site.toString());
        assertThat((String) field(paired, "$.access_token")).isNotBlank();
        assertThat((String) field(paired, "$.refresh_token")).isNotBlank();
        assertThat((String) field(paired, "$.token_type")).isEqualTo("Bearer");
        UUID deviceId = UUID.fromString(field(paired, "$.device_id"));
        assertThat(jdbc.queryForObject("SELECT active FROM device WHERE id = ?", Boolean.class, deviceId)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM device_refresh_tokens WHERE device_id = ?", Integer.class, deviceId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'device.paired' AND entity_id = ?", Integer.class, deviceId))
                .isEqualTo(1);
    }

    @Test
    void aDisplayMustBePairedWithAZoneAndAKioskMustNot() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        UUID zone = createZone(admin, site);

        MvcResult displayWithoutZone = call(post("/api/v1/devices/pairing-codes"), admin,
                "{\"kind\":\"display\",\"site_id\":\"" + site + "\",\"label\":\"Lobby screen\"}");
        assertThat(status(displayWithoutZone)).isEqualTo(400);
        assertThat(errorCode(displayWithoutZone)).isEqualTo("validation_failed");

        MvcResult kioskWithZone = call(post("/api/v1/devices/pairing-codes"), admin,
                "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"zone_id\":\"" + zone + "\",\"label\":\"x\"}");
        assertThat(status(kioskWithZone)).isEqualTo(400);

        String code = pairingCode(admin, "display", site, zone);
        MvcResult paired = pair(code);
        assertThat(status(paired)).isEqualTo(201);
        assertThat((String) field(paired, "$.zone_id")).isEqualTo(zone.toString());
    }

    @Test
    void aPairingCodeIsSingleUseAndExpires() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        String reused = pairingCode(admin, "kiosk", site, null);
        assertThat(status(pair(reused))).isEqualTo(201);

        MvcResult replay = pair(reused);
        assertThat(status(replay)).isEqualTo(401);
        assertThat(errorCode(replay)).isEqualTo("token_invalid");

        String expiring = pairingCode(admin, "kiosk", site, null);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        MvcResult expired = pair(expiring);
        assertThat(status(expired)).isEqualTo(401);
        assertThat(errorCode(expired)).isEqualTo("token_invalid");

        assertThat(status(pair("not-a-real-code"))).isEqualTo(401);
    }

    // ---- refresh (NFR-SEC-005, API-014 precedent) --------------------------------------------------------------

    @Test
    void refreshRotatesTheDeviceTokenAndReuseRevokesTheWholeFamily() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        MvcResult paired = pair(pairingCode(admin, "kiosk", site, null));
        String firstRefresh = field(paired, "$.refresh_token");
        UUID deviceId = UUID.fromString(field(paired, "$.device_id"));

        MvcResult refreshed = call(post("/api/v1/devices/refresh"), null, "{\"refresh_token\":\"" + firstRefresh + "\"}");
        assertThat(status(refreshed)).as(body(refreshed)).isEqualTo(200);
        String secondRefresh = field(refreshed, "$.refresh_token");
        assertThat(secondRefresh).isNotBlank().isNotEqualTo(firstRefresh);

        MvcResult replay = call(post("/api/v1/devices/refresh"), null, "{\"refresh_token\":\"" + firstRefresh + "\"}");
        assertThat(status(replay)).isEqualTo(401);
        assertThat(errorCode(replay)).isEqualTo("token_invalid");
        assertThat(status(call(post("/api/v1/devices/refresh"), null, "{\"refresh_token\":\"" + secondRefresh + "\"}")))
                .as("the legitimate newer token is dead too, reuse revokes the whole family").isEqualTo(401);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM device_refresh_tokens WHERE device_id = ? AND revoked_at IS NULL", Integer.class, deviceId))
                .isZero();
    }

    // ---- heartbeat and bootstrap (FR-OPS-041, SRS §20.4) ---------------------------------------------------------

    @Test
    void heartbeatUpdatesLastSeenAndVersionAndIsRejectedForAnotherDevice() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        MvcResult a = pair(pairingCode(admin, "kiosk", site, null));
        MvcResult b = pair(pairingCode(admin, "kiosk", site, null));
        String tokenA = field(a, "$.access_token");
        UUID idA = UUID.fromString(field(a, "$.device_id"));
        UUID idB = UUID.fromString(field(b, "$.device_id"));

        MvcResult ok = call(post("/api/v1/devices/" + idA + "/heartbeat"), tokenA, "{\"app_version\":\"1.2.3\"}");
        assertThat(status(ok)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT last_app_version FROM device WHERE id = ?", String.class, idA)).isEqualTo("1.2.3");
        assertThat(jdbc.queryForObject("SELECT last_heartbeat_at FROM device WHERE id = ?", java.sql.Timestamp.class, idA)).isNotNull();

        MvcResult wrongDevice = call(post("/api/v1/devices/" + idB + "/heartbeat"), tokenA, "{\"app_version\":\"1.2.3\"}");
        assertThat(status(wrongDevice)).isEqualTo(403);
    }

    @Test
    void bootstrapReturnsBrandingLanguagesLayoutAndServiceTreeScopedToTheDevice() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        UUID zone = createZone(admin, site);
        MvcResult kioskPaired = pair(pairingCode(admin, "kiosk", site, null));
        MvcResult displayPaired = pair(pairingCode(admin, "display", site, zone));

        MvcResult kioskBootstrap = call(get("/api/v1/config/bootstrap"), field(kioskPaired, "$.access_token"), null);
        assertThat(status(kioskBootstrap)).as(body(kioskBootstrap)).isEqualTo(200);
        assertThat((String) field(kioskBootstrap, "$.branding.default_language")).isEqualTo("bn");
        assertThat((List<String>) field(kioskBootstrap, "$.languages")).containsExactly("bn", "en");
        assertThat((List<?>) field(kioskBootstrap, "$.service_tree")).isEmpty();
        assertThat((Object) field(kioskBootstrap, "$.layout")).as("a kiosk has no zone layout").isNull();

        MvcResult displayBootstrap = call(get("/api/v1/config/bootstrap"), field(displayPaired, "$.access_token"), null);
        assertThat(status(displayBootstrap)).isEqualTo(200);
        assertThat((String) field(displayBootstrap, "$.layout.zone.id")).isEqualTo(zone.toString());

        // a staff token, even an admin one, cannot call the device-only bootstrap endpoint
        assertThat(status(call(get("/api/v1/config/bootstrap"), admin, null))).isEqualTo(403);
    }

    // ---- kiosk-scoped service tree (ticket 25, SRS §8.2) -----------------------------------------------------------

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

    @Test
    void aKiosksBootstrapOnlyOffersServicesItCanIssueAndDropsAGroupLeftEmpty() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        UUID mixedGroup = newGroup(site, "GM");
        UUID kioskService = newService(mixedGroup, "KM", "[\"kiosk\",\"reception\"]");
        newService(mixedGroup, "RM", "[\"reception\"]");
        UUID receptionOnlyGroup = newGroup(site, "GR");
        newService(receptionOnlyGroup, "RR", "[\"reception\"]");
        MvcResult kioskPaired = pair(pairingCode(admin, "kiosk", site, null));

        MvcResult bootstrap = call(get("/api/v1/config/bootstrap"), field(kioskPaired, "$.access_token"), null);

        assertThat(status(bootstrap)).as(body(bootstrap)).isEqualTo(200);
        List<Map<String, Object>> tree = field(bootstrap, "$.service_tree");
        assertThat(tree).as("the reception-only group has nothing left to offer at the kiosk").hasSize(1);
        assertThat((String) field(bootstrap, "$.service_tree[0].id")).isEqualTo(mixedGroup.toString());
        assertThat((List<String>) field(bootstrap, "$.service_tree[0].services[*].id")).containsExactly(kioskService.toString());
    }

    // ---- fleet administration: list, get, revoke, permissions -----------------------------------------------------

    @Test
    void theHealthViewShowsConnectivityAndRevokeDropsTheDeviceAndItsCredential() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        MvcResult paired = pair(pairingCode(admin, "kiosk", site, null));
        String deviceToken = field(paired, "$.access_token");
        String refreshToken = field(paired, "$.refresh_token");
        UUID id = UUID.fromString(field(paired, "$.device_id"));

        MvcResult beforeHeartbeat = call(get("/api/v1/devices/" + id), admin, null);
        assertThat((String) field(beforeHeartbeat, "$.connectivity")).isEqualTo("offline");

        call(post("/api/v1/devices/" + id + "/heartbeat"), deviceToken, "{\"app_version\":\"1.0.0\"}");
        MvcResult afterHeartbeat = call(get("/api/v1/devices/" + id), admin, null);
        assertThat((String) field(afterHeartbeat, "$.connectivity")).isEqualTo("online");

        MvcResult list = call(get("/api/v1/devices"), admin, null);
        assertThat(status(list)).isEqualTo(200);
        assertThat(((List<String>) field(list, "$.items[*].id"))).contains(id.toString());

        MvcResult revoked = call(post("/api/v1/devices/" + id + "/revoke"), admin, null);
        assertThat(status(revoked)).as(body(revoked)).isEqualTo(200);
        assertThat((Boolean) field(revoked, "$.active")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'device.revoked' AND entity_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM device_refresh_tokens WHERE device_id = ? AND revoked_at IS NULL", Integer.class, id))
                .isZero();

        MvcResult refreshAfterRevoke = call(post("/api/v1/devices/refresh"), null, "{\"refresh_token\":\"" + refreshToken + "\"}");
        assertThat(errorCode(refreshAfterRevoke)).isEqualTo("token_invalid");
        MvcResult heartbeatAfterRevoke = call(post("/api/v1/devices/" + id + "/heartbeat"), deviceToken, "{\"app_version\":\"1.0.0\"}");
        assertThat(errorCode(heartbeatAfterRevoke)).isEqualTo("token_invalid");
    }

    @Test
    void onlySystemAndOrgAdminManageTheFleet() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        String agent = tokenFor(Role.AGENT, site);

        assertThat(status(call(get("/api/v1/devices"), agent, null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/devices/pairing-codes"), agent,
                        "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"label\":\"x\"}")))
                .isEqualTo(403);
        assertThat(status(call(get("/api/v1/devices"), null, null))).isEqualTo(401);
    }

    // ---- command push (FR-OPS-042) ---------------------------------------------------------------------------------

    @Test
    void pushingACommandToARevokedDeviceIsAConflictAndAnUnknownCommandIsAValidationError() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        MvcResult paired = pair(pairingCode(admin, "kiosk", site, null));
        UUID id = UUID.fromString(field(paired, "$.device_id"));

        MvcResult badCommand = call(post("/api/v1/devices/" + id + "/commands"), admin, "{\"command\":\"shutdown\"}");
        assertThat(status(badCommand)).isEqualTo(400);

        MvcResult reload = call(post("/api/v1/devices/" + id + "/commands"), admin, "{\"command\":\"reload\"}");
        assertThat(status(reload)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'device.command_pushed' AND entity_id = ?", Integer.class, id))
                .isEqualTo(1);

        call(post("/api/v1/devices/" + id + "/revoke"), admin, null);
        MvcResult afterRevoke = call(post("/api/v1/devices/" + id + "/commands"), admin, "{\"command\":\"reload\"}");
        assertThat(status(afterRevoke)).isEqualTo(409);
        assertThat(errorCode(afterRevoke)).isEqualTo("conflict");
    }
}
