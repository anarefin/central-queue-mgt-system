package com.qms.configuration.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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
 * Ticket 06 over HTTP against real PostgreSQL: service groups, services, counter links, teams, outcome codes, soft
 * deactivation, scope, permissions and the audit trail (FR-CFG-010..015, FR-AGT-032, FR-AGT-033, FR-I18N-010,
 * FR-CFG-102, FR-SEC-040).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, CatalogueAdminIT.TicketedServices.class})
class CatalogueAdminIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Services that "have tickets" as far as the delete guard is concerned; ticket issuance does not exist yet. */
    static final Set<UUID> TICKETED = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class TicketedServices {
        @Bean
        @Primary
        ServiceUsage ticketedServices() {
            return TICKETED::contains;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-catalogue");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired CatalogueService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    /** A staff user holding one role, optionally limited to some sites or service groups. */
    private String tokenFor(UUID user, Role role, UUID[] sites, UUID[] groups) throws Exception {
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
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.access_token");
    }

    private String tokenFor(Role role, UUID... sites) throws Exception {
        return tokenFor(createUser(role.wire()), role, sites, new UUID[0]);
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

    private UUID id(MvcResult created) throws Exception {
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return UUID.fromString(field(created, "$.id"));
    }

    /** A site with Bangla as default language and English also enabled. */
    private UUID createSite(String token) throws Exception {
        return id(call(post("/api/v1/sites"), token,
                "{\"name\":\"Main campus\",\"code\":\"" + unique("S") + "\",\"timezone\":\"Asia/Dhaka\",\"address\":\"1 Campus Road\","
                        + "\"default_language\":\"bn\",\"enabled_languages\":[\"bn\",\"en\"]}"));
    }

    private UUID createCounter(String token, UUID site, String label) throws Exception {
        UUID zone = id(call(post("/api/v1/sites/" + site + "/zones"), token, "{\"name\":\"Ground waiting\",\"floor_label\":\"Ground\"}"));
        return id(call(post("/api/v1/zones/" + zone + "/counters"), token, "{\"label\":\"" + label + "\"}"));
    }

    private UUID createGroup(String token, UUID site, String prefix) throws Exception {
        return id(call(post("/api/v1/sites/" + site + "/service-groups"), token,
                "{\"name_i18n\":{\"bn\":\"বহির্বিভাগ\",\"en\":\"Outpatient\"},\"token_prefix\":\"" + prefix + "\",\"display_order\":2}"));
    }

    private static String serviceJson(String prefix) {
        return "{\"name_i18n\":{\"bn\":\"পরামর্শ\",\"en\":\"Consultation\"},\"token_prefix\":\"" + prefix + "\",\"expected_minutes\":12,\"sla_wait_minutes\":30,"
                + "\"channels\":[\"kiosk\",\"reception\"],\"icon\":\"stethoscope\",\"display_order\":3,\"visitor_identifier\":\"mandatory\",\"booking_mode\":\"walk_in_only\"}";
    }

    private UUID createService(String token, UUID group, String prefix) throws Exception {
        return id(call(post("/api/v1/service-groups/" + group + "/services"), token, serviceJson(prefix)));
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
        UUID group = createGroup(admin, site, "OPD");
        UUID svc = createService(admin, group, "CON");
        UUID counter = createCounter(admin, site, "1");
        UUID outcome = id(call(post("/api/v1/services/" + svc + "/outcome-codes"), admin, "{\"code\":\"resolved\",\"label_i18n\":{\"bn\":\"সমাধান\"}}"));

        for (Role role : Role.values()) {
            String token = tokenFor(role);
            boolean allowed = role == Role.SYSTEM_ADMIN || role == Role.ORG_ADMIN;
            int ok = allowed ? 200 : 403;

            assertThat(status(call(get("/api/v1/sites/" + site + "/service-groups"), token, null))).as(role + " list groups").isEqualTo(ok);
            assertThat(status(call(post("/api/v1/sites/" + site + "/service-groups"), token, "{\"name_i18n\":{\"bn\":\"গ\"},\"token_prefix\":\"G\"}")))
                    .as(role + " create group").isEqualTo(allowed ? 201 : 403);
            assertThat(status(call(get("/api/v1/service-groups/" + group), token, null))).as(role + " get group").isEqualTo(ok);
            assertThat(status(call(patch("/api/v1/service-groups/" + group), token, "{\"display_order\":2}"))).as(role + " patch group").isEqualTo(ok);
            assertThat(status(call(get("/api/v1/service-groups/" + group + "/services"), token, null))).as(role + " list services").isEqualTo(ok);
            assertThat(status(call(post("/api/v1/service-groups/" + group + "/services"), token, serviceJson("SVC")))).as(role + " create service").isEqualTo(allowed ? 201 : 403);
            assertThat(status(call(get("/api/v1/service-groups/" + group + "/counters"), token, null))).as(role + " counter options").isEqualTo(ok);
            assertThat(status(call(get("/api/v1/services/" + svc), token, null))).as(role + " get service").isEqualTo(ok);
            assertThat(status(call(patch("/api/v1/services/" + svc), token, "{\"icon\":\"stethoscope\"}"))).as(role + " patch service").isEqualTo(ok);
            assertThat(status(call(put("/api/v1/services/" + svc + "/counters/" + counter), token, "{\"preference_weight\":1}"))).as(role + " link").isEqualTo(ok);
            assertThat(status(call(get("/api/v1/services/" + svc + "/counters"), token, null))).as(role + " list links").isEqualTo(ok);
            assertThat(status(call(get("/api/v1/services/" + svc + "/outcome-codes"), token, null))).as(role + " list outcomes").isEqualTo(ok);
            assertThat(status(call(post("/api/v1/services/" + svc + "/outcome-codes"), token, "{\"code\":\"" + unique("c").replace('-', '_') + "\",\"label_i18n\":{\"bn\":\"x\"}}")))
                    .as(role + " create outcome").isEqualTo(allowed ? 201 : 403);
            assertThat(status(call(patch("/api/v1/outcome-codes/" + outcome), token, "{\"display_order\":1}"))).as(role + " patch outcome").isEqualTo(ok);
            assertThat(status(call(get("/api/v1/service-groups/" + group + "/team"), token, null))).as(role + " get team").isEqualTo(ok);
            assertThat(status(call(post("/api/v1/services/" + svc + "/deactivate"), token, null))).as(role + " deactivate service").isEqualTo(ok);
            assertThat(status(call(post("/api/v1/services/" + svc + "/activate"), token, null))).as(role + " activate service").isEqualTo(ok);
            assertThat(status(call(delete("/api/v1/services/" + svc + "/counters/" + counter), token, null))).as(role + " unlink").isEqualTo(allowed ? 204 : 403);
        }
        assertThat(status(call(get("/api/v1/sites/" + site + "/service-groups"), null, null))).isEqualTo(401);
        assertThat(status(call(post("/api/v1/service-groups/" + group + "/services"), null, serviceJson("X")))).isEqualTo(401);
    }

    @Test
    void permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(UUID.randomUUID().toString()).claim("roles", List.of("agent")).build();
        var authorities = com.qms.platform.security.PermissionMatrix.authoritiesFor(Set.of(Role.AGENT)).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));

        assertThatThrownBy(() -> service.groups(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteService(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.linkCounter(UUID.randomUUID(), UUID.randomUUID(), 1)).isInstanceOf(AccessDeniedException.class);
    }

    // ---- service groups and translatable names -----------------------------------------------------------------

    @Test
    void aServiceGroupCarriesPerLanguageNamesPrefixOrderAndActiveFlagAndIsAuditedWithItsTeam() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);

        MvcResult created = call(post("/api/v1/sites/" + site + "/service-groups"), token,
                "{\"name_i18n\":{\"bn\":\"বহির্বিভাগ\",\"en\":\"Outpatient\"},\"token_prefix\":\"OPD\",\"display_order\":2}");

        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((String) field(created, "$.site_id")).isEqualTo(site.toString());
        assertThat((String) field(created, "$.name_i18n.bn")).isEqualTo("বহির্বিভাগ");
        assertThat((String) field(created, "$.name_i18n.en")).isEqualTo("Outpatient");
        assertThat((String) field(created, "$.token_prefix")).isEqualTo("OPD");
        assertThat((Integer) field(created, "$.display_order")).isEqualTo(2);
        assertThat((Boolean) field(created, "$.active")).isTrue();
        assertThat((List<String>) field(created, "$.missing_translations")).isEmpty();
        UUID group = UUID.fromString(field(created, "$.id"));
        assertThat((String) audit("service_group.created", group).get("after")).contains("Outpatient").contains("OPD");

        // One team per group, created with it.
        MvcResult team = call(get("/api/v1/service-groups/" + group + "/team"), token, null);
        assertThat(status(team)).isEqualTo(200);
        assertThat((String) field(team, "$.service_group_id")).isEqualTo(group.toString());
        assertThat((List<Object>) field(team, "$.members")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE service_group_id = ?", Integer.class, group)).isEqualTo(1);

        MvcResult edited = call(patch("/api/v1/service-groups/" + group), token, "{\"token_prefix\":\"OP\",\"display_order\":1,\"name_i18n\":{\"bn\":\"বহির্বিভাগ\",\"en\":\"Out-patient\"}}");
        assertThat((String) field(edited, "$.token_prefix")).isEqualTo("OP");
        var update = audit("service_group.updated", group);
        assertThat((String) update.get("before")).contains("OPD").contains("Outpatient");
        assertThat((String) update.get("after")).contains("OP").contains("Out-patient");
        assertThat(status(call(patch("/api/v1/service-groups/" + group), token, "{\"token_prefix\":\"OP\"}"))).isEqualTo(200);
        assertThat(auditCount("service_group.updated", group)).as("a no-op edit is not a change").isEqualTo(1);

        assertThat((List<String>) field(call(get("/api/v1/sites/" + site + "/service-groups"), token, null), "$.items[*].id")).containsExactly(group.toString());
        assertThat(status(call(post("/api/v1/sites/" + site + "/service-groups"), token, "{\"name_i18n\":{\"bn\":\"গ\"},\"token_prefix\":\"a-b\"}"))).isEqualTo(400);
        assertThat(status(call(get("/api/v1/sites/" + UUID.randomUUID() + "/service-groups"), token, null))).isEqualTo(404);
    }

    @Test
    void aMissingTranslationWarnsButNeverBlocksAndAMissingDefaultLanguageNameIsRefused() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);

        // Bangla is the site default; English is enabled but left blank: saved, with a warning naming it (FR-I18N-010).
        MvcResult partial = call(post("/api/v1/sites/" + site + "/service-groups"), token, "{\"name_i18n\":{\"bn\":\"বহির্বিভাগ\",\"en\":\"\"},\"token_prefix\":\"OPD\"}");
        assertThat(status(partial)).as(body(partial)).isEqualTo(201);
        assertThat((List<String>) field(partial, "$.missing_translations")).containsExactly("en");
        assertThat((Map<String, String>) field(partial, "$.name_i18n")).containsOnlyKeys("bn");
        UUID group = UUID.fromString(field(partial, "$.id"));
        assertThat((List<String>) field(call(get("/api/v1/service-groups/" + group), token, null), "$.missing_translations")).containsExactly("en");

        // The same holds for services and outcome codes.
        MvcResult service = call(post("/api/v1/service-groups/" + group + "/services"), token, serviceJson("CON").replace("\"en\":\"Consultation\"", "\"en\":\"\""));
        assertThat(status(service)).isEqualTo(201);
        assertThat((List<String>) field(service, "$.missing_translations")).containsExactly("en");
        UUID svc = UUID.fromString(field(service, "$.id"));
        MvcResult outcome = call(post("/api/v1/services/" + svc + "/outcome-codes"), token, "{\"code\":\"referred\",\"label_i18n\":{\"bn\":\"রেফার\"}}");
        assertThat((List<String>) field(outcome, "$.missing_translations")).containsExactly("en");

        // Filling the translation in clears the warning.
        MvcResult filled = call(patch("/api/v1/service-groups/" + group), token, "{\"name_i18n\":{\"bn\":\"বহির্বিভাগ\",\"en\":\"Outpatient\"}}");
        assertThat((List<String>) field(filled, "$.missing_translations")).isEmpty();

        String[][] refused = {
            {"{\"name_i18n\":{\"en\":\"Outpatient\"},\"token_prefix\":\"O\"}", "default_language_required"},
            {"{\"name_i18n\":{\"bn\":\"x\",\"fr\":\"y\"},\"token_prefix\":\"O\"}", "unknown_language"},
            {"{\"name_i18n\":{},\"token_prefix\":\"O\"}", "NotBlank"},
            {"{\"token_prefix\":\"O\"}", "NotBlank"},
        };
        for (String[] c : refused) {
            MvcResult result = call(post("/api/v1/sites/" + site + "/service-groups"), token, c[0]);
            assertThat(status(result)).as(c[0]).isEqualTo(400);
            assertThat(errorCode(result)).isEqualTo("validation_failed");
            assertThat(body(result)).as(c[0]).contains("name_i18n").contains(c[1]);
        }
    }

    // ---- services ----------------------------------------------------------------------------------------------

    @Test
    void aServiceCarriesEveryConfiguredFieldAndIsAuditedWithBeforeAndAfterValues() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID group = createGroup(token, site, "OPD");

        MvcResult created = call(post("/api/v1/service-groups/" + group + "/services"), token, serviceJson("CON"));

        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((String) field(created, "$.service_group_id")).isEqualTo(group.toString());
        assertThat((String) field(created, "$.site_id")).isEqualTo(site.toString());
        assertThat((String) field(created, "$.name_i18n.bn")).isEqualTo("পরামর্শ");
        assertThat((String) field(created, "$.name_i18n.en")).isEqualTo("Consultation");
        assertThat((String) field(created, "$.token_prefix")).isEqualTo("CON");
        assertThat((Integer) field(created, "$.expected_minutes")).isEqualTo(12);
        assertThat((Integer) field(created, "$.sla_wait_minutes")).isEqualTo(30);
        assertThat((List<String>) field(created, "$.channels")).containsExactly("kiosk", "reception");
        assertThat((String) field(created, "$.icon")).isEqualTo("stethoscope");
        assertThat((Integer) field(created, "$.display_order")).isEqualTo(3);
        assertThat((String) field(created, "$.visitor_identifier")).isEqualTo("mandatory");
        assertThat((String) field(created, "$.booking_mode")).isEqualTo("walk_in_only");
        assertThat((Boolean) field(created, "$.active")).isTrue();
        UUID svc = UUID.fromString(field(created, "$.id"));
        assertThat((String) audit("service.created", svc).get("after")).contains("CON").contains("walk_in_only").contains("mandatory").contains("stethoscope");

        // Defaults: identifier not required, walk-ins and appointments both, every channel, primary display order.
        MvcResult plain = call(post("/api/v1/service-groups/" + group + "/services"), token,
                "{\"name_i18n\":{\"bn\":\"পরীক্ষা\"},\"token_prefix\":\"LAB\",\"expected_minutes\":5,\"sla_wait_minutes\":20}");
        assertThat((String) field(plain, "$.visitor_identifier")).isEqualTo("not_required");
        assertThat((String) field(plain, "$.booking_mode")).isEqualTo("both");
        assertThat((List<String>) field(plain, "$.channels")).containsExactly("kiosk", "reception", "mobile", "appointment_checkin");
        assertThat((Object) field(plain, "$.icon")).isNull();

        MvcResult edited = call(patch("/api/v1/services/" + svc), token,
                "{\"expected_minutes\":15,\"sla_wait_minutes\":45,\"channels\":[\"mobile\"],\"visitor_identifier\":\"optional\",\"booking_mode\":\"appointment_only\",\"icon\":\"\",\"display_order\":1}");
        assertThat(status(edited)).as(body(edited)).isEqualTo(200);
        assertThat((Integer) field(edited, "$.expected_minutes")).isEqualTo(15);
        assertThat((String) field(edited, "$.booking_mode")).isEqualTo("appointment_only");
        assertThat((Object) field(edited, "$.icon")).as("an empty icon clears it").isNull();
        var update = audit("service.updated", svc);
        assertThat((String) update.get("before")).contains("walk_in_only").contains("mandatory");
        assertThat((String) update.get("after")).contains("appointment_only").contains("optional");
        assertThat(status(call(patch("/api/v1/services/" + svc), token, "{\"display_order\":1}"))).isEqualTo(200);
        assertThat(auditCount("service.updated", svc)).as("a no-op edit is not a change").isEqualTo(1);

        assertThat((List<String>) field(call(get("/api/v1/service-groups/" + group + "/services"), token, null), "$.items[*].token_prefix")).as("by display order").containsExactly("LAB", "CON");
    }

    @Test
    void invalidServiceFieldsAreRefusedWithTheFieldNamed() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID group = createGroup(token, createSite(token), "OPD");
        String good = serviceJson("CON");
        String[][] cases = {
            {good.replace("\"expected_minutes\":12,", ""), "expected_minutes"},
            {good.replace("\"expected_minutes\":12", "\"expected_minutes\":0"), "expected_minutes"},
            {good.replace("\"sla_wait_minutes\":30", "\"sla_wait_minutes\":-1"), "sla_wait_minutes"},
            {good.replace("\"sla_wait_minutes\":30,", ""), "sla_wait_minutes"},
            {good.replace("\"CON\"", "\"C O N\""), "token_prefix"},
            {good.replace("\"CON\"", "\"\""), "token_prefix"},
            {good.replace("\"kiosk\"", "\"telegraph\""), "channels"},
            {good.replace("[\"kiosk\",\"reception\"]", "[\"kiosk\",\"kiosk\"]"), "channels"},
            {good.replace("mandatory", "sometimes"), "visitor_identifier"},
            {good.replace("walk_in_only", "whenever"), "booking_mode"},
            {good.replace("\"display_order\":3", "\"display_order\":-3"), "display_order"},
        };
        for (String[] c : cases) {
            MvcResult result = call(post("/api/v1/service-groups/" + group + "/services"), token, c[0]);
            assertThat(status(result)).as(c[0]).isEqualTo(400);
            assertThat(errorCode(result)).isEqualTo("validation_failed");
            assertThat(body(result)).as(c[0]).contains("\"" + c[1] + "\"");
        }
        assertThat((List<Object>) field(call(get("/api/v1/service-groups/" + group + "/services"), token, null), "$.items")).isEmpty();
    }

    // ---- counter links -----------------------------------------------------------------------------------------

    @Test
    void servicesAreLinkedToCountersWithAPreferenceWeightThatCanBeChanged() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID group = createGroup(token, site, "OPD");
        UUID svc = createService(token, group, "CON");
        UUID other = createService(token, group, "LAB");
        UUID primary = createCounter(token, site, "1");
        UUID fallback = createCounter(token, site, "2");

        MvcResult linked = call(put("/api/v1/services/" + svc + "/counters/" + fallback), token, "{\"preference_weight\":2}");
        assertThat(status(linked)).as(body(linked)).isEqualTo(200);
        assertThat((Integer) field(linked, "$.preference_weight")).isEqualTo(2);
        MvcResult primaryLink = call(put("/api/v1/services/" + svc + "/counters/" + primary), token, null);
        assertThat((Integer) field(primaryLink, "$.preference_weight")).as("weight defaults to 1, the primary counter").isEqualTo(1);
        call(put("/api/v1/services/" + other + "/counters/" + primary), token, "{\"preference_weight\":1}");
        assertThat((String) audit("service.counter_linked", svc).get("after")).contains("\"preference_weight\"");

        // A counter serves several services; a service is served at several counters, primary first.
        MvcResult links = call(get("/api/v1/services/" + svc + "/counters"), token, null);
        assertThat((List<String>) field(links, "$.items[*].counter_id")).containsExactly(primary.toString(), fallback.toString());
        assertThat((List<Integer>) field(links, "$.items[*].preference_weight")).containsExactly(1, 2);
        assertThat((List<String>) field(links, "$.items[*].counter_label")).containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counter_service WHERE counter_id = ?", Integer.class, primary)).isEqualTo(2);

        MvcResult reweighted = call(put("/api/v1/services/" + svc + "/counters/" + fallback), token, "{\"preference_weight\":3}");
        assertThat((Integer) field(reweighted, "$.preference_weight")).isEqualTo(3);
        var change = audit("service.counter_link_updated", svc);
        assertThat((String) change.get("before")).contains("2");
        assertThat((String) change.get("after")).contains("3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counter_service WHERE service_id = ?", Integer.class, svc)).as("re-linking updates in place").isEqualTo(2);

        assertThat(status(call(put("/api/v1/services/" + svc + "/counters/" + fallback), token, "{\"preference_weight\":0}"))).isEqualTo(400);
        assertThat(status(call(put("/api/v1/services/" + svc + "/counters/" + UUID.randomUUID()), token, null))).isEqualTo(400);

        assertThat(status(call(delete("/api/v1/services/" + svc + "/counters/" + fallback), token, null))).isEqualTo(204);
        assertThat(auditCount("service.counter_unlinked", svc)).isEqualTo(1);
        assertThat(status(call(delete("/api/v1/services/" + svc + "/counters/" + fallback), token, null))).isEqualTo(404);
        assertThat((List<String>) field(call(get("/api/v1/services/" + svc + "/counters"), token, null), "$.items[*].counter_id")).containsExactly(primary.toString());

        MvcResult options = call(get("/api/v1/service-groups/" + group + "/counters"), token, null);
        assertThat((List<String>) field(options, "$.items[*].id")).containsExactlyInAnyOrder(primary.toString(), fallback.toString());
        assertThat((List<String>) field(options, "$.items[*].zone_name")).contains("Ground waiting");
    }

    @Test
    void aCounterOfAnotherSiteOrAnInactiveCounterCannotBeLinked() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID svc = createService(token, createGroup(token, site, "OPD"), "CON");
        UUID elsewhere = createCounter(token, createSite(token), "9");
        UUID counter = createCounter(token, site, "1");

        MvcResult differentSite = call(put("/api/v1/services/" + svc + "/counters/" + elsewhere), token, null);
        assertThat(status(differentSite)).isEqualTo(400);
        assertThat(body(differentSite)).contains("counter_id").contains("different_site");

        call(post("/api/v1/counters/" + counter + "/deactivate"), token, null);
        assertThat(status(call(put("/api/v1/services/" + svc + "/counters/" + counter), token, null))).isEqualTo(409);
    }

    // ---- teams and approval ------------------------------------------------------------------------------------

    @Test
    void anOrgAdminChangesTheTeamDirectlyAndItIsAudited() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID group = createGroup(token, createSite(token), "OPD");
        UUID agent = createUser("agent");

        MvcResult added = call(post("/api/v1/service-groups/" + group + "/team/members"), token, "{\"user_id\":\"" + agent + "\"}");

        assertThat(status(added)).as(body(added)).isEqualTo(200);
        assertThat((List<String>) field(added, "$.members[*].user_id")).containsExactly(agent.toString());
        assertThat((List<String>) field(added, "$.members[*].display_name")).containsExactly("agent");
        UUID team = UUID.fromString(field(added, "$.id"));
        assertThat((String) audit("team.member_added", team).get("after")).contains(agent.toString());
        assertThat(status(call(post("/api/v1/service-groups/" + group + "/team/members"), token, "{\"user_id\":\"" + agent + "\"}"))).isEqualTo(200);
        assertThat(auditCount("team.member_added", team)).as("adding a member twice changes nothing").isEqualTo(1);

        assertThat(status(call(post("/api/v1/service-groups/" + group + "/team/members"), token, "{\"user_id\":\"" + UUID.randomUUID() + "\"}"))).isEqualTo(400);
        assertThat(status(call(post("/api/v1/service-groups/" + group + "/team/members"), token, "{}"))).isEqualTo(400);
        UUID disabled = createUser("agent");
        jdbc.update("UPDATE users SET active = false WHERE id = ?", disabled);
        MvcResult refused = call(post("/api/v1/service-groups/" + group + "/team/members"), token, "{\"user_id\":\"" + disabled + "\"}");
        assertThat(status(refused)).isEqualTo(400);
        assertThat(body(refused)).contains("user_id").contains("user_inactive");

        MvcResult removed = call(delete("/api/v1/service-groups/" + group + "/team/members/" + agent), token, null);
        assertThat(status(removed)).isEqualTo(200);
        assertThat((List<Object>) field(removed, "$.members")).isEmpty();
        assertThat((String) audit("team.member_removed", team).get("before")).contains(agent.toString());
        assertThat(status(call(delete("/api/v1/service-groups/" + group + "/team/members/" + agent), token, null))).isEqualTo(404);
    }

    @Test
    void aTeamAdminsMembershipChangeTakesEffectOnlyOnceAnOrgAdminApprovesIt() throws Exception {
        String admin = tokenFor(Role.ORG_ADMIN);
        UUID group = createGroup(admin, createSite(admin), "OPD");
        UUID agent = createUser("agent");
        UUID teamAdminUser = createUser("team_admin");
        String teamAdmin = tokenFor(teamAdminUser, Role.TEAM_ADMIN, new UUID[0], new UUID[] {group});
        String request = "{\"type\":\"team_member\",\"payload\":{\"group_id\":\"" + group + "\",\"user_id\":\"" + agent + "\"}}";

        // A Team Admin has no way to change the team directly, only to ask.
        assertThat(status(call(post("/api/v1/service-groups/" + group + "/team/members"), teamAdmin, "{\"user_id\":\"" + agent + "\"}"))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/service-groups/" + group + "/team"), teamAdmin, null))).isEqualTo(403);
        MvcResult asked = call(post("/api/v1/approvals"), teamAdmin, request);
        assertThat(status(asked)).as(body(asked)).isEqualTo(201);
        String approval = field(asked, "$.id");
        assertThat((List<Object>) field(call(get("/api/v1/service-groups/" + group + "/team"), admin, null), "$.members")).as("nothing changes while pending").isEmpty();
        assertThat(status(call(post("/api/v1/approvals/" + approval + "/approve"), teamAdmin, null))).as("a Team Admin cannot approve").isEqualTo(403);

        MvcResult approved = call(post("/api/v1/approvals/" + approval + "/approve"), admin, null);
        assertThat(status(approved)).as(body(approved)).isEqualTo(200);
        MvcResult team = call(get("/api/v1/service-groups/" + group + "/team"), admin, null);
        assertThat((List<String>) field(team, "$.members[*].user_id")).containsExactly(agent.toString());
        UUID teamId = UUID.fromString(field(team, "$.id"));
        var applied = audit("team.member_added", teamId);
        assertThat((String) applied.get("after")).contains(approval);
        assertThat(applied.get("actor_role")).isEqualTo("org_admin");

        // A rejected request changes nothing; an approved removal takes the member out again.
        UUID rejected = createUser("agent");
        MvcResult rejectedAsk = call(post("/api/v1/approvals"), teamAdmin,
                "{\"type\":\"team_member\",\"payload\":{\"group_id\":\"" + group + "\",\"user_id\":\"" + rejected + "\"}}");
        assertThat(status(call(post("/api/v1/approvals/" + field(rejectedAsk, "$.id") + "/reject"), admin, null))).isEqualTo(200);
        MvcResult removal = call(post("/api/v1/approvals"), teamAdmin,
                "{\"type\":\"team_member\",\"payload\":{\"group_id\":\"" + group + "\",\"user_id\":\"" + agent + "\",\"action\":\"remove\"}}");
        assertThat(status(call(post("/api/v1/approvals/" + field(removal, "$.id") + "/approve"), admin, null))).isEqualTo(200);
        assertThat((List<Object>) field(call(get("/api/v1/service-groups/" + group + "/team"), admin, null), "$.members")).isEmpty();
        assertThat(auditCount("team.member_added", teamId)).isEqualTo(1);
        assertThat(auditCount("team.member_removed", teamId)).isEqualTo(1);

        // An approval that cannot be applied fails whole: the request stays pending.
        UUID gone = UUID.randomUUID();
        MvcResult unknownUser = call(post("/api/v1/approvals"), teamAdmin,
                "{\"type\":\"team_member\",\"payload\":{\"group_id\":\"" + group + "\",\"user_id\":\"" + gone + "\"}}");
        assertThat(status(call(post("/api/v1/approvals/" + field(unknownUser, "$.id") + "/approve"), admin, null))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_requests WHERE id = ?::uuid", String.class, (String) field(unknownUser, "$.id"))).isEqualTo("pending");
    }

    // ---- outcome codes -----------------------------------------------------------------------------------------

    @Test
    void outcomeCodesAreConfiguredPerServiceWithLocalisedLabelsAndOnlyEverDeactivated() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID svc = createService(token, createGroup(token, createSite(token), "OPD"), "CON");
        UUID otherService = createService(token, UUID.fromString(field(call(get("/api/v1/services/" + svc), token, null), "$.service_group_id")), "LAB");

        MvcResult created = call(post("/api/v1/services/" + svc + "/outcome-codes"), token,
                "{\"code\":\"docs_missing\",\"label_i18n\":{\"bn\":\"নথি অনুপস্থিত\",\"en\":\"Documents missing\"},\"display_order\":4}");

        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((String) field(created, "$.code")).isEqualTo("docs_missing");
        assertThat((String) field(created, "$.label_i18n.bn")).isEqualTo("নথি অনুপস্থিত");
        assertThat((String) field(created, "$.label_i18n.en")).isEqualTo("Documents missing");
        assertThat((String) field(created, "$.service_id")).isEqualTo(svc.toString());
        assertThat((Boolean) field(created, "$.active")).isTrue();
        UUID outcome = UUID.fromString(field(created, "$.id"));
        assertThat((String) audit("outcome_code.created", outcome).get("after")).contains("docs_missing").contains("Documents missing");

        call(post("/api/v1/services/" + svc + "/outcome-codes"), token, "{\"code\":\"resolved\",\"label_i18n\":{\"bn\":\"সমাধান\",\"en\":\"Resolved\"},\"display_order\":1}");
        assertThat((List<String>) field(call(get("/api/v1/services/" + svc + "/outcome-codes"), token, null), "$.items[*].code")).containsExactly("resolved", "docs_missing");
        assertThat((List<Object>) field(call(get("/api/v1/services/" + otherService + "/outcome-codes"), token, null), "$.items")).as("codes belong to one service").isEmpty();

        // The same code may exist on another service, but not twice on one.
        assertThat(status(call(post("/api/v1/services/" + otherService + "/outcome-codes"), token, "{\"code\":\"resolved\",\"label_i18n\":{\"bn\":\"x\"}}"))).isEqualTo(201);
        MvcResult duplicate = call(post("/api/v1/services/" + svc + "/outcome-codes"), token, "{\"code\":\"RESOLVED\",\"label_i18n\":{\"bn\":\"x\"}}");
        assertThat(status(duplicate)).isEqualTo(400); // upper case is not a valid code
        MvcResult again = call(post("/api/v1/services/" + svc + "/outcome-codes"), token, "{\"code\":\"resolved\",\"label_i18n\":{\"bn\":\"x\"}}");
        assertThat(status(again)).isEqualTo(409);
        assertThat(errorCode(again)).isEqualTo("conflict");

        MvcResult relabelled = call(patch("/api/v1/outcome-codes/" + outcome), token, "{\"label_i18n\":{\"bn\":\"নথি নেই\",\"en\":\"No documents\"},\"display_order\":9,\"code\":\"other\"}");
        assertThat((String) field(relabelled, "$.label_i18n.en")).isEqualTo("No documents");
        assertThat((String) field(relabelled, "$.code")).as("the code itself never changes").isEqualTo("docs_missing");
        assertThat((String) audit("outcome_code.updated", outcome).get("before")).contains("Documents missing");

        MvcResult off = call(post("/api/v1/outcome-codes/" + outcome + "/deactivate"), token, "{\"reason\":\"retired\"}");
        assertThat((Boolean) field(off, "$.active")).isFalse();
        assertThat(audit("outcome_code.deactivated", outcome).get("reason")).isEqualTo("retired");
        assertThat(status(call(get("/api/v1/outcome-codes/" + outcome), token, null))).as("still resolves").isEqualTo(200);
        assertThat(status(call(delete("/api/v1/outcome-codes/" + outcome), token, null))).isEqualTo(405);
        assertThat((Boolean) field(call(post("/api/v1/outcome-codes/" + outcome + "/activate"), token, null), "$.active")).isTrue();
    }

    // ---- soft deactivation and deletion ------------------------------------------------------------------------

    @Test
    void deactivatingAGroupDeactivatesItsServicesAndNothingActiveSitsUnderAnInactiveParent() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID group = createGroup(token, site, "OPD");
        UUID svc = createService(token, group, "CON");

        MvcResult off = call(post("/api/v1/service-groups/" + group + "/deactivate"), token, "{\"reason\":\"clinic closed\"}");

        assertThat((Boolean) field(off, "$.active")).isFalse();
        assertThat((Boolean) field(call(get("/api/v1/services/" + svc), token, null), "$.active")).as("the service still resolves, inactive").isFalse();
        assertThat(audit("service_group.deactivated", group).get("reason")).isEqualTo("clinic closed");
        assertThat(audit("service.deactivated", svc).get("reason")).isEqualTo("parent service group deactivated");
        assertThat(status(call(post("/api/v1/service-groups/" + group + "/services"), token, serviceJson("NEW")))).isEqualTo(409);
        assertThat(status(call(post("/api/v1/services/" + svc + "/activate"), token, null))).as("service under an inactive group").isEqualTo(409);

        assertThat(status(call(post("/api/v1/service-groups/" + group + "/activate"), token, null))).isEqualTo(200);
        assertThat((Boolean) field(call(get("/api/v1/services/" + svc), token, null), "$.active")).as("children stay inactive").isFalse();
        assertThat(status(call(post("/api/v1/services/" + svc + "/activate"), token, null))).isEqualTo(200);
        assertThat(auditCount("service_group.activated", group)).isEqualTo(1);

        call(post("/api/v1/sites/" + site + "/deactivate"), token, null);
        assertThat(status(call(post("/api/v1/sites/" + site + "/service-groups"), token, "{\"name_i18n\":{\"bn\":\"গ\"},\"token_prefix\":\"G\"}"))).as("group under an inactive site").isEqualTo(409);
        assertThat(status(call(delete("/api/v1/service-groups/" + group), token, null))).as("groups are never deleted").isEqualTo(405);
    }

    @Test
    void aServiceWithTicketsCannotBeDeletedOnlyDeactivatedAndAnUnusedOneGoesWithItsLinksAndCodes() throws Exception {
        String token = tokenFor(Role.ORG_ADMIN);
        UUID site = createSite(token);
        UUID group = createGroup(token, site, "OPD");
        UUID used = createService(token, group, "CON");
        UUID unused = createService(token, group, "LAB");
        UUID counter = createCounter(token, site, "1");
        call(put("/api/v1/services/" + unused + "/counters/" + counter), token, null);
        call(post("/api/v1/services/" + unused + "/outcome-codes"), token, "{\"code\":\"resolved\",\"label_i18n\":{\"bn\":\"সমাধান\"}}");
        TICKETED.add(used);

        MvcResult blocked = call(delete("/api/v1/services/" + used), token, null);
        assertThat(status(blocked)).isEqualTo(409);
        assertThat(errorCode(blocked)).isEqualTo("conflict");
        assertThat(body(blocked)).contains("service_has_tickets");
        assertThat(status(call(get("/api/v1/services/" + used), token, null))).as("still there").isEqualTo(200);
        assertThat(auditCount("service.deleted", used)).isZero();
        MvcResult off = call(post("/api/v1/services/" + used + "/deactivate"), token, "{\"reason\":\"retired\"}");
        assertThat(status(off)).as("deactivation is the way out").isEqualTo(200);
        assertThat((Boolean) field(off, "$.active")).isFalse();
        assertThat(status(call(delete("/api/v1/services/" + used), token, null))).as("still blocked once inactive").isEqualTo(409);

        assertThat(status(call(delete("/api/v1/services/" + unused), token, null))).isEqualTo(204);
        assertThat(status(call(get("/api/v1/services/" + unused), token, null))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counter_service WHERE service_id = ?", Integer.class, unused)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outcome_code WHERE service_id = ?", Integer.class, unused)).isZero();
        assertThat((String) audit("service.deleted", unused).get("before")).contains("LAB");
    }

    @Test
    void theRealTicketCheckLooksAtTheTicketTableWhenThereIsOne() throws Exception {
        UUID used = UUID.randomUUID();
        UUID unused = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            var single = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var usage = new JdbcServiceUsage(single);
            assertThat(usage.hasTickets(used)).as("no ticket table yet, so nothing can have tickets").isFalse();

            single.execute("CREATE TEMP TABLE ticket (id uuid PRIMARY KEY, service_id uuid NOT NULL)");
            single.update("INSERT INTO ticket (id, service_id) VALUES (?, ?)", UUID.randomUUID(), used);
            assertThat(usage.hasTickets(used)).isTrue();
            assertThat(usage.hasTickets(unused)).isFalse();
            single.execute("DROP TABLE ticket"); // the connection goes back to the pool
        }
    }

    // ---- scope -------------------------------------------------------------------------------------------------

    @Test
    void aSiteScopedAdminManagesTheCatalogueOfTheirOwnSitesOnly() throws Exception {
        String orgWide = tokenFor(Role.ORG_ADMIN);
        UUID mine = createSite(orgWide);
        UUID other = createSite(orgWide);
        UUID otherGroup = createGroup(orgWide, other, "OTH");
        UUID otherService = createService(orgWide, otherGroup, "OSV");
        UUID otherOutcome = id(call(post("/api/v1/services/" + otherService + "/outcome-codes"), orgWide, "{\"code\":\"resolved\",\"label_i18n\":{\"bn\":\"x\"}}"));
        String scoped = tokenFor(Role.ORG_ADMIN, mine);

        UUID group = createGroup(scoped, mine, "OPD");
        UUID svc = createService(scoped, group, "CON");
        assertThat(status(call(get("/api/v1/service-groups/" + group + "/team"), scoped, null))).isEqualTo(200);
        assertThat((List<String>) field(call(get("/api/v1/sites/" + mine + "/service-groups"), scoped, null), "$.items[*].id")).containsExactly(group.toString());

        assertThat(status(call(get("/api/v1/sites/" + other + "/service-groups"), scoped, null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/sites/" + other + "/service-groups"), scoped, "{\"name_i18n\":{\"bn\":\"গ\"},\"token_prefix\":\"G\"}"))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/service-groups/" + otherGroup), scoped, null))).isEqualTo(403);
        assertThat(status(call(patch("/api/v1/service-groups/" + otherGroup), scoped, "{\"display_order\":9}"))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/service-groups/" + otherGroup + "/services"), scoped, serviceJson("X")))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/service-groups/" + otherGroup + "/team"), scoped, null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/service-groups/" + otherGroup + "/team/members"), scoped, "{\"user_id\":\"" + createUser("agent") + "\"}"))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/services/" + otherService), scoped, null))).isEqualTo(403);
        assertThat(status(call(patch("/api/v1/services/" + otherService), scoped, "{\"display_order\":9}"))).isEqualTo(403);
        assertThat(status(call(delete("/api/v1/services/" + otherService), scoped, null))).isEqualTo(403);
        assertThat(status(call(put("/api/v1/services/" + svc + "/counters/" + createCounter(orgWide, other, "9")), scoped, null))).as("a counter outside their sites").isEqualTo(400);
        assertThat(status(call(patch("/api/v1/outcome-codes/" + otherOutcome), scoped, "{\"display_order\":9}"))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/services/" + otherService + "/outcome-codes"), scoped, "{\"code\":\"x\",\"label_i18n\":{\"bn\":\"x\"}}"))).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT display_order FROM service_group WHERE id = ?", Integer.class, otherGroup)).isEqualTo(2);
    }
}
