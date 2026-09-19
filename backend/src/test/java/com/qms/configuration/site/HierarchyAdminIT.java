package com.qms.configuration.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.PermissionMatrix;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket 05 over HTTP against real PostgreSQL: sites, zones and counters, soft deactivation, scope, permissions and the
 * audit trail (FR-CFG-001..004, FR-I18N-002, NFR-SCL-002, FR-SEC-040).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class HierarchyAdminIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-hierarchy");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired HierarchyService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** A staff user holding one role, optionally limited to some sites, created straight in the database. */
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

    private static String siteJson(String code) {
        return "{\"name\":\"Main campus\",\"code\":\"" + code + "\",\"timezone\":\"Asia/Dhaka\",\"address\":\"1 Campus Road, Dhaka\","
                + "\"default_language\":\"bn\",\"enabled_languages\":[\"bn\",\"en\"]}";
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID createSite(String token) throws Exception {
        MvcResult created = call(post("/api/v1/sites"), token, siteJson(unique("S")));
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private UUID createZone(String token, UUID site) throws Exception {
        MvcResult created = call(post("/api/v1/sites/" + site + "/zones"), token,
                "{\"name\":\"Ground waiting\",\"floor_label\":\"Ground\",\"building_label\":\"Block B\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private UUID createCounter(String token, UUID zone, String label) throws Exception {
        MvcResult created = call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"" + label + "\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    private Map<String, Object> audit(String action, UUID entityId) {
        return jdbc.queryForMap(
                "SELECT actor_id, actor_role, before::text AS before, after::text AS after, reason FROM audit_log WHERE action = ? AND entity_id = ? ORDER BY occurred_at DESC LIMIT 1",
                action, entityId);
    }

    private int auditCount(String action, UUID entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
    }

    // ---- permissions -------------------------------------------------------------------------------------------

    @Test
    void everyEndpointFollowsThePermissionMatrixForEveryRole() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(admin);
        UUID zone = createZone(admin, site);
        UUID counter = createCounter(admin, zone, "1");

        for (Role role : Role.values()) {
            String token = tokenFor(role);
            boolean allowed = role == Role.SYSTEM_ADMIN || role == Role.ORG_ADMIN;
            assertThat(PermissionMatrix.authoritiesFor(Set.of(role)).contains("perm:config:org_sites_zones")).isEqualTo(allowed);

            assertThat(status(call(get("/api/v1/sites"), token, null))).as(role + " GET /sites").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(post("/api/v1/sites"), token, siteJson(unique("R"))))).as(role + " POST /sites").isEqualTo(allowed ? 201 : 403);
            assertThat(status(call(get("/api/v1/sites/" + site), token, null))).as(role + " GET site").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(patch("/api/v1/sites/" + site), token, "{\"address\":\"same\"}"))).as(role + " PATCH site").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(get("/api/v1/sites/" + site + "/zones"), token, null))).as(role + " GET zones").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(post("/api/v1/sites/" + site + "/zones"), token, "{\"name\":\"Z\",\"floor_label\":\"1st\"}")))
                    .as(role + " POST zone").isEqualTo(allowed ? 201 : 403);
            assertThat(status(call(patch("/api/v1/zones/" + zone), token, "{\"name\":\"Ground waiting\"}"))).as(role + " PATCH zone").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(get("/api/v1/zones/" + zone + "/counters"), token, null))).as(role + " GET counters").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"C\"}"))).as(role + " POST counter").isEqualTo(allowed ? 201 : 403);
            assertThat(status(call(patch("/api/v1/counters/" + counter), token, "{\"label\":\"1\"}"))).as(role + " PATCH counter").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(post("/api/v1/counters/" + counter + "/deactivate"), token, null))).as(role + " deactivate").isEqualTo(allowed ? 200 : 403);
            assertThat(status(call(post("/api/v1/counters/" + counter + "/activate"), token, null))).as(role + " activate").isEqualTo(allowed ? 200 : 403);
        }
        assertThat(status(call(get("/api/v1/sites"), null, null))).isEqualTo(401);
        assertThat(status(call(post("/api/v1/sites"), null, siteJson(unique("N"))))).isEqualTo(401);
    }

    @Test
    void permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(UUID.randomUUID().toString()).claim("roles", List.of("agent")).build();
        var authorities = PermissionMatrix.authoritiesFor(Set.of(Role.AGENT)).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));

        assertThatThrownBy(() -> service.sites()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.createSite("A", "A", "Asia/Dhaka", "x", "en", null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deactivateSite(UUID.randomUUID(), null)).isInstanceOf(AccessDeniedException.class);
    }

    // ---- sites -------------------------------------------------------------------------------------------------

    @Test
    void aSiteCarriesTimezoneAddressAndOrderedLanguagesStoresUtcAndIsAuditedOnCreateAndRename() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        MvcResult created = call(post("/api/v1/sites"), token, siteJson(unique("HQ")));

        assertThat(status(created)).isEqualTo(201);
        assertThat((String) field(created, "$.timezone")).isEqualTo("Asia/Dhaka");
        assertThat((String) field(created, "$.address")).isEqualTo("1 Campus Road, Dhaka");
        assertThat((String) field(created, "$.default_language")).isEqualTo("bn");
        assertThat((List<String>) field(created, "$.enabled_languages")).containsExactly("bn", "en");
        assertThat((Boolean) field(created, "$.active")).isTrue();
        UUID id = UUID.fromString(field(created, "$.id"));

        // Timestamps are UTC instants (offset Z); the timezone only says how to render them (FR-CFG-002).
        String createdAt = field(created, "$.created_at");
        assertThat(createdAt).endsWith("Z");
        assertThat(Instant.parse(createdAt)).isBefore(Instant.now().plusSeconds(5));
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM created_at) FROM site WHERE id = ?", Double.class, id))
                .isCloseTo(Instant.parse(createdAt).toEpochMilli() / 1000.0, org.assertj.core.data.Offset.offset(0.001));

        var createdAudit = audit("site.created", id);
        assertThat(createdAudit.get("actor_role")).isEqualTo("org_admin");
        assertThat((String) createdAudit.get("after")).contains("Asia/Dhaka").contains("1 Campus Road, Dhaka").contains("Main campus");
        assertThat(createdAudit.get("before")).isNull();

        MvcResult renamed = call(patch("/api/v1/sites/" + id), token, "{\"name\":\"North campus\",\"timezone\":\"Asia/Kolkata\",\"enabled_languages\":[\"en\",\"bn\"]}");
        assertThat(status(renamed)).isEqualTo(200);
        assertThat((String) field(renamed, "$.name")).isEqualTo("North campus");
        assertThat((List<String>) field(renamed, "$.enabled_languages")).containsExactly("en", "bn");
        var renameAudit = audit("site.updated", id);
        assertThat((String) renameAudit.get("before")).contains("Main campus").contains("Asia/Dhaka");
        assertThat((String) renameAudit.get("after")).contains("North campus").contains("Asia/Kolkata");

        MvcResult unchanged = call(patch("/api/v1/sites/" + id), token, "{\"name\":\"North campus\"}");
        assertThat(status(unchanged)).isEqualTo(200);
        assertThat(auditCount("site.updated", id)).as("a no-op edit is not a change").isEqualTo(1);

        MvcResult listed = call(get("/api/v1/sites"), token, null);
        assertThat((List<String>) field(listed, "$.items[*].id")).contains(id.toString());
    }

    @Test
    void invalidTimezonesLanguagesAndCodesAreRefusedWithTheFieldNamed() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        String code = unique("V");
        assertThat(status(call(post("/api/v1/sites"), token, siteJson(code)))).isEqualTo(201);

        String[][] cases = {
            {siteJson(unique("V")).replace("Asia/Dhaka", "Mars/Olympus"), "timezone"},
            {siteJson(unique("V")).replace("\"bn\",\"en\"", "\"bn\",\"fr\""), "enabled_languages"},
            {siteJson(unique("V")).replace("\"default_language\":\"bn\"", "\"default_language\":\"fr\""), "default_language"},
            {siteJson(unique("V")).replace("[\"bn\",\"en\"]", "[\"en\"]"), "enabled_languages"},
            {siteJson(unique("V")).replace("[\"bn\",\"en\"]", "[\"bn\",\"bn\"]"), "enabled_languages"},
            {siteJson(unique("V")).replace("Main campus", "   "), "name"},
            {siteJson(unique("V")).replace("1 Campus Road, Dhaka", ""), "address"},
        };
        for (String[] c : cases) {
            MvcResult result = call(post("/api/v1/sites"), token, c[0]);
            assertThat(status(result)).as(c[0]).isEqualTo(400);
            assertThat(errorCode(result)).isEqualTo("validation_failed");
            assertThat(body(result)).as(c[0]).contains("\"" + c[1] + "\"");
        }

        MvcResult duplicate = call(post("/api/v1/sites"), token, siteJson(code.toLowerCase()));
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat(errorCode(duplicate)).isEqualTo("conflict");
    }

    @Test
    void addingSitesNeedsNoCodeChangeOrRestart() throws Exception {
        String token = tokenFor(Role.SYSTEM_ADMIN);

        UUID first = createSite(token);
        UUID second = createSite(token);
        UUID zone = createZone(token, second);

        List<String> ids = field(call(get("/api/v1/sites"), token, null), "$.items[*].id");
        assertThat(ids).contains(first.toString(), second.toString());
        assertThat((List<String>) field(call(get("/api/v1/sites/" + second + "/zones"), token, null), "$.items[*].id")).containsExactly(zone.toString());
    }

    // ---- zones and counters ------------------------------------------------------------------------------------

    @Test
    void aZoneNeedsAFloorLabelAndMayHaveABuildingLabelThatCanBeCleared() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);

        MvcResult noFloor = call(post("/api/v1/sites/" + site + "/zones"), token, "{\"name\":\"Lobby\"}");
        assertThat(status(noFloor)).isEqualTo(400);
        assertThat(body(noFloor)).contains("floor_label");

        UUID zone = createZone(token, site);
        MvcResult read = call(get("/api/v1/zones/" + zone), token, null);
        assertThat((String) field(read, "$.floor_label")).isEqualTo("Ground");
        assertThat((String) field(read, "$.building_label")).isEqualTo("Block B");
        assertThat((String) field(read, "$.site_id")).isEqualTo(site.toString());
        assertThat((String) audit("zone.created", zone).get("after")).contains("Block B").contains("Ground");

        MvcResult noBuilding = call(post("/api/v1/sites/" + site + "/zones"), token, "{\"name\":\"Annex\",\"floor_label\":\"3rd\"}");
        assertThat(status(noBuilding)).isEqualTo(201);
        assertThat((Object) field(noBuilding, "$.building_label")).isNull();

        MvcResult edited = call(patch("/api/v1/zones/" + zone), token, "{\"floor_label\":\"1st\",\"building_label\":\"\"}");
        assertThat((String) field(edited, "$.floor_label")).isEqualTo("1st");
        assertThat((Object) field(edited, "$.building_label")).isNull();
        var editAudit = audit("zone.updated", zone);
        assertThat((String) editAudit.get("before")).contains("Ground").contains("Block B");
        assertThat((String) editAudit.get("after")).contains("1st");
    }

    @Test
    void aCounterHasAShortLabelAZoneAndAnOptionalLocationNote() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID zone = createZone(token, site);

        MvcResult created = call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"Counter 3\",\"location_note\":\"Behind the pillar\"}");
        assertThat(status(created)).isEqualTo(201);
        assertThat((String) field(created, "$.label")).isEqualTo("Counter 3");
        assertThat((String) field(created, "$.zone_id")).isEqualTo(zone.toString());
        assertThat((String) field(created, "$.site_id")).isEqualTo(site.toString());
        assertThat((String) field(created, "$.location_note")).isEqualTo("Behind the pillar");
        UUID counter = UUID.fromString(field(created, "$.id"));
        assertThat((String) audit("counter.created", counter).get("after")).contains("Counter 3").contains("Behind the pillar");

        MvcResult plain = call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"4\"}");
        assertThat((Object) field(plain, "$.location_note")).isNull();
        assertThat(status(call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"" + "x".repeat(31) + "\"}"))).isEqualTo(400);
        assertThat(status(call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\" \"}"))).isEqualTo(400);

        MvcResult renamed = call(patch("/api/v1/counters/" + counter), token, "{\"label\":\"Counter 03\"}");
        assertThat((String) field(renamed, "$.label")).isEqualTo("Counter 03");
        assertThat((String) audit("counter.updated", counter).get("before")).contains("Counter 3");
        assertThat(status(call(get("/api/v1/zones/" + UUID.randomUUID() + "/counters"), token, null))).isEqualTo(404);
        assertThat((List<String>) field(call(get("/api/v1/zones/" + zone + "/counters"), token, null), "$.items[*].label")).containsExactly("4", "Counter 03");
    }

    // ---- soft deactivation -------------------------------------------------------------------------------------

    @Test
    void deactivationIsSoftSoHistoricalReferencesKeepResolving() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID zone = createZone(token, site);
        UUID counter = createCounter(token, zone, "1");

        MvcResult deactivated = call(post("/api/v1/sites/" + site + "/deactivate"), token, "{\"reason\":\"campus closed\"}");

        assertThat(status(deactivated)).isEqualTo(200);
        assertThat((Boolean) field(deactivated, "$.active")).isFalse();
        // Everything below is deactivated with it, and every one of them still resolves by id.
        assertThat((Boolean) field(call(get("/api/v1/sites/" + site), token, null), "$.active")).isFalse();
        assertThat((Boolean) field(call(get("/api/v1/zones/" + zone), token, null), "$.active")).isFalse();
        MvcResult counterRead = call(get("/api/v1/counters/" + counter), token, null);
        assertThat(status(counterRead)).isEqualTo(200);
        assertThat((Boolean) field(counterRead, "$.active")).isFalse();
        assertThat((String) field(counterRead, "$.label")).isEqualTo("1");
        assertThat((List<String>) field(call(get("/api/v1/sites"), token, null), "$.items[*].id")).contains(site.toString());

        var siteAudit = audit("site.deactivated", site);
        assertThat((String) siteAudit.get("before")).contains("true");
        assertThat((String) siteAudit.get("after")).contains("false");
        assertThat(siteAudit.get("reason")).isEqualTo("campus closed");
        assertThat(audit("zone.deactivated", zone).get("reason")).isEqualTo("parent site deactivated");
        assertThat(audit("counter.deactivated", counter).get("reason")).isEqualTo("parent zone deactivated");

        // There is no way to delete: no endpoint, and the database refuses a delete of a referenced row.
        assertThat(status(call(delete("/api/v1/sites/" + site), token, null))).isEqualTo(405);
        assertThat(status(call(delete("/api/v1/zones/" + zone), token, null))).isEqualTo(405);
        assertThat(status(call(delete("/api/v1/counters/" + counter), token, null))).isEqualTo(405);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM site WHERE id = ?", site)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM zone WHERE id = ?", zone)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anActiveZoneOrCounterNeverSitsUnderAnInactiveParent() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID zone = createZone(token, site);
        UUID counter = createCounter(token, zone, "1");
        call(post("/api/v1/zones/" + zone + "/deactivate"), token, null);

        // Deactivating a zone takes its counters; nothing may be added below it.
        assertThat((Boolean) field(call(get("/api/v1/counters/" + counter), token, null), "$.active")).isFalse();
        MvcResult addCounter = call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"2\"}");
        assertThat(status(addCounter)).isEqualTo(409);
        assertThat(errorCode(addCounter)).isEqualTo("conflict");

        // Reactivating the zone does not silently bring the counter back; that is a deliberate act.
        assertThat(status(call(post("/api/v1/zones/" + zone + "/activate"), token, null))).isEqualTo(200);
        assertThat((Boolean) field(call(get("/api/v1/counters/" + counter), token, null), "$.active")).isFalse();
        assertThat(status(call(post("/api/v1/counters/" + counter + "/activate"), token, null))).isEqualTo(200);
        assertThat((Boolean) field(call(get("/api/v1/counters/" + counter), token, null), "$.active")).isTrue();

        call(post("/api/v1/sites/" + site + "/deactivate"), token, null);
        assertThat(status(call(post("/api/v1/sites/" + site + "/zones"), token, "{\"name\":\"New\",\"floor_label\":\"2nd\"}"))).isEqualTo(409);
        assertThat(status(call(post("/api/v1/zones/" + zone + "/activate"), token, null))).as("zone under an inactive site").isEqualTo(409);
        assertThat(status(call(post("/api/v1/sites/" + site + "/activate"), token, null))).isEqualTo(200);
        assertThat((Boolean) field(call(get("/api/v1/zones/" + zone), token, null), "$.active")).as("children stay inactive").isFalse();
        assertThat(status(call(post("/api/v1/zones/" + zone + "/activate"), token, null))).isEqualTo(200);
        assertThat(auditCount("site.activated", site)).isEqualTo(1);
        assertThat(auditCount("zone.activated", zone)).isEqualTo(2);
    }

    // ---- scope -------------------------------------------------------------------------------------------------

    @Test
    void aSiteScopedAdminSeesAndChangesOnlyTheirOwnSites() throws Exception {
        String orgWide = tokenFor(Role.ORG_ADMIN);
        UUID mine = createSite(orgWide);
        UUID other = createSite(orgWide);
        UUID otherZone = createZone(orgWide, other);
        UUID otherCounter = createCounter(orgWide, otherZone, "9");
        String scoped = tokenFor(Role.ORG_ADMIN, mine);

        List<String> visible = field(call(get("/api/v1/sites"), scoped, null), "$.items[*].id");
        assertThat(visible).containsExactly(mine.toString());
        assertThat(status(call(get("/api/v1/sites/" + mine), scoped, null))).isEqualTo(200);
        assertThat(status(call(post("/api/v1/sites/" + mine + "/zones"), scoped, "{\"name\":\"Z\",\"floor_label\":\"1st\"}"))).isEqualTo(201);

        assertThat(status(call(get("/api/v1/sites/" + other), scoped, null))).isEqualTo(403);
        assertThat(status(call(patch("/api/v1/sites/" + other), scoped, "{\"name\":\"Hijack\"}"))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/sites/" + other + "/deactivate"), scoped, null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/sites/" + other + "/zones"), scoped, "{\"name\":\"Z\",\"floor_label\":\"1st\"}"))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/zones/" + otherZone), scoped, null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/zones/" + otherZone + "/counters"), scoped, "{\"label\":\"x\"}"))).isEqualTo(403);
        assertThat(status(call(patch("/api/v1/counters/" + otherCounter), scoped, "{\"label\":\"x\"}"))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/sites"), scoped, siteJson(unique("X"))))).as("a new site is outside every scope").isEqualTo(403);
        assertThat((String) jdbc.queryForObject("SELECT name FROM site WHERE id = ?", String.class, other)).isEqualTo("Main campus");
    }
}
