package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.configuration.approval.ApprovalDecided;
import com.qms.platform.security.PermissionMatrix;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ticket 04 over HTTP against real PostgreSQL: the §5.2 matrix, escalation guards, audit search/export, approvals. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AdminApiIT.Recorders.class})
class AdminApiIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final UUID SITE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID SITE_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID GROUP_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID GROUP_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final Path KEY_DIR = newKeyDir();

    /** Records the effects that later tickets consume, so tests can assert they are raised. */
    @TestConfiguration
    static class Recorders {
        final List<UUID> principalChanged = Collections.synchronizedList(new ArrayList<>());
        final List<ApprovalDecided> decisions = Collections.synchronizedList(new ArrayList<>());

        @Bean
        @Primary
        PrincipalChangedPublisher recordingPublisher() {
            return principalChanged::add;
        }

        @EventListener
        void on(ApprovalDecided event) {
            decisions.add(event);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-admin");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleAssignmentRepository roles;
    @Autowired PasswordService passwords;
    @Autowired UserAdminService userAdmin;
    @Autowired Recorders recorders;

    record TestUser(UUID id, String username) {}

    @BeforeEach
    void clearRecorders() {
        recorders.principalChanged.clear();
        recorders.decisions.clear();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private TestUser user(String prefix, RoleAssignment... assignments) {
        String username = prefix + "-" + UUID.randomUUID();
        UUID id = users.insert(username, passwords.hash(PASSWORD), prefix, "en", Instant.now());
        roles.replaceAll(id, List.of(assignments));
        return new TestUser(id, username);
    }

    private static RoleAssignment org(Role role) {
        return new RoleAssignment(role, Set.of(), Set.of());
    }

    private MvcResult loginResult(TestUser user) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.username() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
    }

    private String token(TestUser user) throws Exception {
        MvcResult result = loginResult(user);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String errorCode(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.error.code");
    }

    private static String createUserBody(String username, String password, String rolesJson) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"display_name\":\"Created\",\"roles\":" + rolesJson + "}";
    }

    private static String roleJson(String role, UUID site, UUID group) {
        return "{\"role\":\"" + role + "\""
                + (site == null ? "" : ",\"site_ids\":[\"" + site + "\"]")
                + (group == null ? "" : ",\"group_ids\":[\"" + group + "\"]") + "}";
    }

    private int auditCount(String action, UUID entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
    }

    private static JwtAuthenticationToken authentication(UUID sub, Role role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(sub.toString()).claim("roles", List.of(role.wire())).build();
        var authorities = PermissionMatrix.authoritiesFor(Set.of(role)).stream().map(SimpleGrantedAuthority::new).toList();
        return new JwtAuthenticationToken(jwt, authorities);
    }

    // ---- the §5.2 matrix over HTTP -----------------------------------------------------------------------------

    @Test
    void userAdministrationFollowsThePermissionMatrixForEveryRole() throws Exception {
        for (Role role : Role.values()) {
            String token = token(user(role.wire(), org(role)));
            boolean allowed = role == Role.SYSTEM_ADMIN || role == Role.ORG_ADMIN;
            String body = createUserBody("made-" + UUID.randomUUID(), PASSWORD, "[]");

            MvcResult created = call(post("/api/v1/users"), token, body);
            MvcResult listed = call(get("/api/v1/users"), token, null);

            assertThat(status(created)).as(role + " POST /users").isEqualTo(allowed ? 201 : 403);
            assertThat(status(listed)).as(role + " GET /users").isEqualTo(allowed ? 200 : 403);
            if (!allowed) assertThat(errorCode(created)).isEqualTo("forbidden");
        }
        assertThat(status(call(get("/api/v1/users"), null, null))).isEqualTo(401);
    }

    @Test
    void anOrgAdminCreatesAUserWithRolesAndTheAuditEntryHasActorRoleBeforeAndAfter() throws Exception {
        TestUser admin = user("orgadmin", org(Role.ORG_ADMIN));
        String username = "new-" + UUID.randomUUID();

        MvcResult created = call(post("/api/v1/users"), token(admin),
                createUserBody(username, PASSWORD, "[" + roleJson("agent", null, GROUP_1) + "]"));

        assertThat(status(created)).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.username")).isEqualTo(username);
        assertThat((String) JsonPath.read(body, "$.roles[0].role")).isEqualTo("agent");
        assertThat(body).doesNotContain("password").doesNotContain("$2a$");
        UUID newId = UUID.fromString(JsonPath.read(body, "$.id"));

        var row = jdbc.queryForMap("SELECT actor_id, actor_role, after::text AS after, ip, trace_id FROM audit_log WHERE action = 'user.created' AND entity_id = ?", newId);
        assertThat(row.get("actor_id")).isEqualTo(admin.id());
        assertThat(row.get("actor_role")).isEqualTo("org_admin");
        assertThat((String) row.get("after")).contains(username).contains("agent").doesNotContain(PASSWORD);
        assertThat(row.get("ip")).isNotNull();
        assertThat(row.get("trace_id")).isNotNull();
    }

    @Test
    void duplicateUsernamesAreConflictsCaseInsensitivelyAndWeakPasswordsAreRefused() throws Exception {
        String token = token(user("orgadmin", org(Role.ORG_ADMIN)));
        String username = "dup-" + UUID.randomUUID();
        assertThat(status(call(post("/api/v1/users"), token, createUserBody(username, PASSWORD, "[]")))).isEqualTo(201);

        MvcResult again = call(post("/api/v1/users"), token, createUserBody(username.toUpperCase(), PASSWORD, "[]"));
        assertThat(status(again)).isEqualTo(409);
        assertThat(errorCode(again)).isEqualTo("conflict");

        MvcResult weak = call(post("/api/v1/users"), token, createUserBody("weak-" + UUID.randomUUID(), "short", "[]"));
        assertThat(status(weak)).isEqualTo(400);
        assertThat(weak.getResponse().getContentAsString()).contains("too_short");
    }

    @Test
    void anUnknownRoleNameIsAValidationErrorNotAGrant() throws Exception {
        String token = token(user("orgadmin", org(Role.ORG_ADMIN)));
        MvcResult result = call(post("/api/v1/users"), token,
                createUserBody("x-" + UUID.randomUUID(), PASSWORD, "[{\"role\":\"root\"}]"));
        assertThat(status(result)).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("validation_failed");
    }

    // ---- escalation --------------------------------------------------------------------------------------------

    @Test
    void nobodyGrantsMoreThanTheyHold() throws Exception {
        String orgAdmin = token(user("orgadmin", org(Role.ORG_ADMIN)));
        String sysAdmin = token(user("sysadmin", org(Role.SYSTEM_ADMIN)));
        String siteAdmin = token(user("siteadmin", new RoleAssignment(Role.ORG_ADMIN, Set.of(SITE_A), Set.of())));

        assertThat(status(call(post("/api/v1/users"), orgAdmin,
                createUserBody("e1-" + UUID.randomUUID(), PASSWORD, "[" + roleJson("system_admin", null, null) + "]"))))
                .as("org admin creating a system admin").isEqualTo(403);
        assertThat(status(call(post("/api/v1/users"), sysAdmin,
                createUserBody("e2-" + UUID.randomUUID(), PASSWORD, "[" + roleJson("system_admin", null, null) + "]"))))
                .as("system admin creating a system admin").isEqualTo(201);
        assertThat(status(call(post("/api/v1/users"), siteAdmin,
                createUserBody("e3-" + UUID.randomUUID(), PASSWORD, "[" + roleJson("agent", SITE_A, null) + "]"))))
                .as("site admin inside own site").isEqualTo(201);
        assertThat(status(call(post("/api/v1/users"), siteAdmin,
                createUserBody("e4-" + UUID.randomUUID(), PASSWORD, "[" + roleJson("agent", SITE_B, null) + "]"))))
                .as("site admin outside own site").isEqualTo(403);
        assertThat(status(call(post("/api/v1/users"), siteAdmin,
                createUserBody("e5-" + UUID.randomUUID(), PASSWORD, "[" + roleJson("agent", null, null) + "]"))))
                .as("site admin granting an organisation-wide role").isEqualTo(403);
    }

    // ---- disable, enable, roles --------------------------------------------------------------------------------

    @Test
    void disablingAUserEndsTheirSessionsAuditsItAndRaisesPrincipalChanged() throws Exception {
        TestUser admin = user("orgadmin", org(Role.ORG_ADMIN));
        TestUser target = user("agent", org(Role.AGENT));
        MvcResult targetLogin = loginResult(target);
        String targetAccess = JsonPath.read(targetLogin.getResponse().getContentAsString(), "$.access_token");
        String refresh = targetLogin.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("qms_refresh=")).map(h -> h.substring(12, h.indexOf(';'))).findFirst().orElseThrow();

        MvcResult disabled = call(post("/api/v1/users/" + target.id() + "/disable"), token(admin), "{\"reason\":\"left the company\"}");

        assertThat(status(disabled)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(disabled.getResponse().getContentAsString(), "$.active")).isFalse();
        MvcResult refresh1 = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("qms_refresh", refresh))).andReturn();
        assertThat(errorCode(refresh1)).isEqualTo("token_invalid");
        assertThat(errorCode(loginResult(target))).isEqualTo("invalid_credentials");
        assertThat(status(call(get("/api/v1/auth/me"), targetAccess, null)))
                .as("the current access token stays valid until it expires (API-013)").isEqualTo(200);
        var row = jdbc.queryForMap("SELECT before::text AS before, after::text AS after, reason FROM audit_log WHERE action = 'user.disabled' AND entity_id = ?", target.id());
        assertThat((String) row.get("before")).contains("true");
        assertThat((String) row.get("after")).contains("false");
        assertThat(row.get("reason")).isEqualTo("left the company");
        assertThat(recorders.principalChanged).containsExactly(target.id());
    }

    @Test
    void enablingRestoresSignInAndYouCannotDisableYourselfOrASystemAdministratorAsAnOrgAdmin() throws Exception {
        TestUser admin = user("orgadmin", org(Role.ORG_ADMIN));
        TestUser sys = user("sysadmin", org(Role.SYSTEM_ADMIN));
        TestUser target = user("agent", org(Role.AGENT));
        String token = token(admin);

        call(post("/api/v1/users/" + target.id() + "/disable"), token, null);
        assertThat(status(call(post("/api/v1/users/" + target.id() + "/enable"), token, null))).isEqualTo(200);
        assertThat(status(loginResult(target))).isEqualTo(200);
        assertThat(auditCount("user.enabled", target.id())).isEqualTo(1);

        MvcResult self = call(post("/api/v1/users/" + admin.id() + "/disable"), token, null);
        assertThat(status(self)).isEqualTo(409);
        assertThat(errorCode(self)).isEqualTo("conflict");
        assertThat(status(call(post("/api/v1/users/" + sys.id() + "/disable"), token, null))).isEqualTo(403);
    }

    @Test
    void changingRolesIsAuditedWithBeforeAndAfterAndTakesEffectAtTheNextRefresh() throws Exception {
        TestUser admin = user("orgadmin", org(Role.ORG_ADMIN));
        TestUser target = user("target", org(Role.AGENT));

        MvcResult changed = call(put("/api/v1/users/" + target.id() + "/roles"), token(admin),
                "{\"roles\":[" + roleJson("team_admin", null, GROUP_1) + "],\"reason\":\"promoted\"}");

        assertThat(status(changed)).isEqualTo(200);
        var row = jdbc.queryForMap("SELECT before::text AS before, after::text AS after, reason FROM audit_log WHERE action = 'user.roles.changed' AND entity_id = ?", target.id());
        assertThat((String) row.get("before")).contains("agent");
        assertThat((String) row.get("after")).contains("team_admin").contains(GROUP_1.toString());
        assertThat(row.get("reason")).isEqualTo("promoted");
        assertThat(recorders.principalChanged).containsExactly(target.id());

        MvcResult targetLogin = loginResult(target);
        String access = JsonPath.read(targetLogin.getResponse().getContentAsString(), "$.access_token");
        MvcResult me = call(get("/api/v1/auth/me"), access, null);
        assertThat((String) JsonPath.read(me.getResponse().getContentAsString(), "$.roles[0]")).isEqualTo("team_admin");
        assertThat((String) JsonPath.read(me.getResponse().getContentAsString(), "$.groups[0]")).isEqualTo(GROUP_1.toString());
    }

    @Test
    void profileEditsAreAuditedAndTheUserListPaginates() throws Exception {
        String token = token(user("orgadmin", org(Role.ORG_ADMIN)));
        TestUser target = user("edit", org(Role.AGENT));

        MvcResult edited = call(patch("/api/v1/users/" + target.id()), token, "{\"display_name\":\"Renamed\",\"preferred_language\":\"bn\"}");
        assertThat(status(edited)).isEqualTo(200);
        assertThat((String) JsonPath.read(edited.getResponse().getContentAsString(), "$.preferred_language")).isEqualTo("bn");
        var row = jdbc.queryForMap("SELECT before::text AS before, after::text AS after FROM audit_log WHERE action = 'user.updated' AND entity_id = ?", target.id());
        assertThat((String) row.get("before")).contains("en");
        assertThat((String) row.get("after")).contains("Renamed").contains("bn");

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcResult page = call(get("/api/v1/users?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)), token, null);
            assertThat(status(page)).isEqualTo(200);
            List<String> names = JsonPath.read(page.getResponse().getContentAsString(), "$.items[*].username");
            names.forEach(n -> assertThat(seen.add(n)).as("no duplicates across pages").isTrue());
            cursor = JsonPath.read(page.getResponse().getContentAsString(), "$.next_cursor");
        } while (cursor != null && ++pages < 100);
        assertThat(seen).contains(target.username());
    }

    @Test
    void permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers() {
        TestUser target = user("target", org(Role.AGENT));
        SecurityContextHolder.getContext().setAuthentication(authentication(UUID.randomUUID(), Role.AGENT));

        assertThatThrownBy(() -> userAdmin.disable(target.id(), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> userAdmin.get(target.id())).isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.getContext().setAuthentication(authentication(UUID.randomUUID(), Role.TEAM_ADMIN));
        assertThatThrownBy(() -> userAdmin.replaceRoles(target.id(), List.of(), null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void externalGroupMappingOwnsItsOwnAssignmentsAndNeverTouchesManualOnes() {
        TestUser person = user("mapped", org(Role.TEAM_ADMIN));

        roles.replaceExternal(person.id(), List.of(org(Role.AGENT)));
        assertThat(roles.findByUser(person.id()).stream().map(RoleAssignment::role)).containsExactlyInAnyOrder(Role.TEAM_ADMIN, Role.AGENT);

        roles.replaceExternal(person.id(), List.of(org(Role.RECEPTION_OPERATOR)));
        assertThat(roles.findByUser(person.id()).stream().map(RoleAssignment::role)).containsExactlyInAnyOrder(Role.TEAM_ADMIN, Role.RECEPTION_OPERATOR);

        roles.replaceAll(person.id(), List.of(org(Role.ORG_ADMIN)));
        assertThat(roles.findByUser(person.id()).stream().map(RoleAssignment::role)).containsExactlyInAnyOrder(Role.ORG_ADMIN, Role.RECEPTION_OPERATOR);
    }

    // ---- GET /audit --------------------------------------------------------------------------------------------

    @Test
    void onlyOrgAdminAndSystemAdministratorMayReadTheAuditLog() throws Exception {
        for (Role role : Role.values()) {
            String token = token(user(role.wire(), org(role)));
            boolean allowed = role == Role.SYSTEM_ADMIN || role == Role.ORG_ADMIN;
            MvcResult result = call(get("/api/v1/audit"), token, null);
            assertThat(status(result)).as(role.wire()).isEqualTo(allowed ? 200 : 403);
        }
        assertThat(status(call(get("/api/v1/audit"), null, null))).isEqualTo(401);
    }

    @Test
    void auditSearchFiltersAndPaginatesByCursor() throws Exception {
        TestUser admin = user("orgadmin", org(Role.ORG_ADMIN));
        String token = token(admin);
        TestUser target = user("audited", org(Role.AGENT));
        for (int i = 0; i < 3; i++) {
            call(patch("/api/v1/users/" + target.id()), token, "{\"display_name\":\"Name " + i + "\"}");
        }

        MvcResult first = call(get("/api/v1/audit?entity_id=" + target.id() + "&limit=2"), token, null);
        assertThat(status(first)).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(first.getResponse().getContentAsString(), "$.items")).hasSize(2);
        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.next_cursor");
        assertThat(cursor).isNotBlank();

        MvcResult second = call(get("/api/v1/audit?entity_id=" + target.id() + "&limit=2&cursor=" + cursor), token, null);
        List<String> firstIds = JsonPath.read(first.getResponse().getContentAsString(), "$.items[*].id");
        List<String> secondIds = JsonPath.read(second.getResponse().getContentAsString(), "$.items[*].id");
        assertThat(secondIds).isNotEmpty().doesNotContainAnyElementsOf(firstIds);

        MvcResult byAction = call(get("/api/v1/audit?entity_id=" + target.id() + "&action=user.updated"), token, null);
        List<String> actions = JsonPath.read(byAction.getResponse().getContentAsString(), "$.items[*].action");
        assertThat(actions).hasSize(3).containsOnly("user.updated");
        MvcResult byActor = call(get("/api/v1/audit?entity_id=" + target.id() + "&actor_id=" + admin.id() + "&action=user.*"), token, null);
        assertThat((List<?>) JsonPath.read(byActor.getResponse().getContentAsString(), "$.items")).hasSize(3);
    }

    @Test
    void badAuditQueryParametersAreValidationErrors() throws Exception {
        String token = token(user("orgadmin", org(Role.ORG_ADMIN)));
        assertThat(errorCode(call(get("/api/v1/audit?cursor=not-a-cursor"), token, null))).isEqualTo("validation_failed");
        assertThat(errorCode(call(get("/api/v1/audit?limit=0"), token, null))).isEqualTo("validation_failed");
        assertThat(errorCode(call(get("/api/v1/audit?actor_id=nope"), token, null))).isEqualTo("validation_failed");
        assertThat(status(call(get("/api/v1/audit?limit=100000"), token, null))).as("an oversized limit is clamped").isEqualTo(200);
    }

    @Test
    void csvExportIsSpreadsheetSafeAndItselfAudited() throws Exception {
        TestUser admin = user("orgadmin", org(Role.ORG_ADMIN));
        String token = token(admin);
        TestUser target = user("csv", org(Role.AGENT));
        call(post("/api/v1/users/" + target.id() + "/disable"), token, "{\"reason\":\"=HYPERLINK(\\\"http://evil\\\")\"}");

        MvcResult started = mvc.perform(get("/api/v1/audit?format=csv&entity_id=" + target.id()).header("Authorization", "Bearer " + token)).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andReturn();

        assertThat(done.getResponse().getContentType()).startsWith("text/csv");
        String csv = done.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines().findFirst()).contains("id,actor_id,actor_role,action,entity,entity_id,before,after,ip,device,reason,trace_id,occurred_at");
        assertThat(csv).contains("\"user.disabled\"").contains("\"'=HYPERLINK");
        assertThat(csv).doesNotContain(",\"=HYPERLINK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'audit.exported' AND actor_id = ?", Integer.class, admin.id())).isEqualTo(1);
        assertThat(status(call(get("/api/v1/audit?format=csv"), token(user("agent", org(Role.AGENT))), null))).isEqualTo(403);
    }

    // ---- approvals ---------------------------------------------------------------------------------------------

    private static String approvalBody(String type, UUID group) {
        String extra = type.equals("team_member") ? "\"user_id\":\"" + UUID.randomUUID() + "\"" : "\"counter_id\":\"" + UUID.randomUUID() + "\"";
        return "{\"type\":\"" + type + "\",\"payload\":{\"group_id\":\"" + group + "\"," + extra + "}}";
    }

    /** A real service group with its team, created once, for approvals that are applied when approved. */
    private void ensureServiceGroup(UUID group) {
        if (jdbc.queryForObject("SELECT count(*) FROM service_group WHERE id = ?", Integer.class, group) > 0) return;
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Approval site', ?, 'Asia/Dhaka', 'x', 'en', '[\"en\"]'::jsonb)",
                site, "AP-" + site);
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Group\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Group')", UUID.randomUUID(), group);
    }

    @Test
    void aTeamAdminRequestsAndAnOrgAdminDecidesWithNoRoleElevationAndNothingTakingEffectEarly() throws Exception {
        TestUser teamAdmin = user("team", new RoleAssignment(Role.TEAM_ADMIN, Set.of(), Set.of(GROUP_1)));
        TestUser orgAdmin = user("orgadmin", org(Role.ORG_ADMIN));
        String teamToken = token(teamAdmin);
        String adminToken = token(orgAdmin);
        int rolesBefore = roles.findByUser(teamAdmin.id()).size();
        // Approving a team_member request applies it (ticket 06), so it must name a real group and a real user.
        ensureServiceGroup(GROUP_1);
        TestUser member = user("member", org(Role.AGENT));

        MvcResult requested = call(post("/api/v1/approvals"), teamToken,
                "{\"type\":\"team_member\",\"payload\":{\"group_id\":\"" + GROUP_1 + "\",\"user_id\":\"" + member.id() + "\"}}");

        assertThat(status(requested)).isEqualTo(201);
        String id = JsonPath.read(requested.getResponse().getContentAsString(), "$.id");
        assertThat((String) JsonPath.read(requested.getResponse().getContentAsString(), "$.status")).isEqualTo("pending");
        assertThat(recorders.decisions).as("a pending request has no effect").isEmpty();
        assertThat(roles.findByUser(teamAdmin.id())).as("no temporary role elevation").hasSize(rolesBefore);
        assertThat(roles.findByUser(teamAdmin.id()).getFirst().role()).isEqualTo(Role.TEAM_ADMIN);

        assertThat(status(call(post("/api/v1/approvals/" + id + "/approve"), teamToken, null))).as("team admin cannot decide").isEqualTo(403);
        assertThat(status(call(get("/api/v1/approvals"), teamToken, null))).isEqualTo(403);
        assertThat((List<?>) JsonPath.read(call(get("/api/v1/approvals/mine"), teamToken, null).getResponse().getContentAsString(), "$")).hasSize(1);

        MvcResult listed = call(get("/api/v1/approvals?status=pending"), adminToken, null);
        assertThat((List<String>) JsonPath.read(listed.getResponse().getContentAsString(), "$[*].id")).contains(id);

        MvcResult approved = call(post("/api/v1/approvals/" + id + "/approve"), adminToken, "{\"reason\":\"ok\"}");
        assertThat(status(approved)).isEqualTo(200);
        assertThat((String) JsonPath.read(approved.getResponse().getContentAsString(), "$.status")).isEqualTo("approved");
        assertThat((String) JsonPath.read(approved.getResponse().getContentAsString(), "$.decided_by")).isEqualTo(orgAdmin.id().toString());
        assertThat(recorders.decisions).singleElement().satisfies(event -> {
            assertThat(event.approved()).isTrue();
            assertThat(event.approvalId().toString()).isEqualTo(id);
        });
        assertThat(status(call(post("/api/v1/approvals/" + id + "/reject"), adminToken, null))).as("already decided").isEqualTo(409);
        assertThat(auditCount("approval.requested", UUID.fromString(id))).isEqualTo(1);
        assertThat(auditCount("approval.approved", UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void rejectionCarriesAReasonAndCounterAllocationsFollowTheSameFlow() throws Exception {
        String teamToken = token(user("team", new RoleAssignment(Role.TEAM_ADMIN, Set.of(), Set.of(GROUP_1))));
        String adminToken = token(user("orgadmin", org(Role.ORG_ADMIN)));
        String id = JsonPath.read(call(post("/api/v1/approvals"), teamToken, approvalBody("counter_allocation", GROUP_1)).getResponse().getContentAsString(), "$.id");

        MvcResult rejected = call(post("/api/v1/approvals/" + id + "/reject"), adminToken, "{\"reason\":\"no capacity\"}");

        assertThat(status(rejected)).isEqualTo(200);
        assertThat((String) JsonPath.read(rejected.getResponse().getContentAsString(), "$.status")).isEqualTo("rejected");
        assertThat((String) JsonPath.read(rejected.getResponse().getContentAsString(), "$.decision_reason")).isEqualTo("no capacity");
        assertThat(recorders.decisions).singleElement().satisfies(event -> assertThat(event.approved()).isFalse());
    }

    @Test
    void aRequestForAGroupOutsideTheCallersScopeIsForbiddenAndOtherRolesCannotRequest() throws Exception {
        String teamToken = token(user("team", new RoleAssignment(Role.TEAM_ADMIN, Set.of(), Set.of(GROUP_1))));
        String agentToken = token(user("agent", org(Role.AGENT)));

        assertThat(status(call(post("/api/v1/approvals"), teamToken, approvalBody("team_member", GROUP_2)))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/approvals"), agentToken, approvalBody("team_member", GROUP_1)))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/approvals"), teamToken, "{\"type\":\"team_member\",\"payload\":{\"group_id\":\"" + GROUP_1 + "\"}}")))
                .as("user_id is required").isEqualTo(400);
        assertThat(status(call(post("/api/v1/approvals"), teamToken, "{\"type\":\"bogus\",\"payload\":{}}"))).isEqualTo(400);
    }

    @Test
    void approvalsForOneScopedApproverStayInsideItsGroups() throws Exception {
        String teamToken = token(user("team", new RoleAssignment(Role.TEAM_ADMIN, Set.of(), Set.of(GROUP_1))));
        String id = JsonPath.read(call(post("/api/v1/approvals"), teamToken, approvalBody("team_member", GROUP_1)).getResponse().getContentAsString(), "$.id");
        String otherGroupAdmin = token(user("orgadmin", new RoleAssignment(Role.ORG_ADMIN, Set.of(), Set.of(GROUP_2))));

        assertThat(status(call(post("/api/v1/approvals/" + id + "/approve"), otherGroupAdmin, null))).isEqualTo(403);
    }

    @Test
    void noPasswordOrPasswordHashEverReachesTheAuditTrail() {
        List<String> leaks = jdbc.queryForList(
                "SELECT row_to_json(a)::text FROM audit_log a WHERE row_to_json(a)::text LIKE '%" + PASSWORD + "%' OR row_to_json(a)::text LIKE '%$2a$%'", String.class);
        assertThat(leaks).isEmpty();
    }
}
