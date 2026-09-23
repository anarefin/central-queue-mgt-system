package com.qms.issuance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Authorities;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * Ticket 67 against real PostgreSQL: seeding a Site's starter catalogue and numbering from the active vertical
 * profile end to end -- one shipped profile at a time, idempotency on a second seed, the token-prefix clash rule,
 * and the two-permission requirement. {@link SetupWizardIT} covers the rest of the wizard and is unaffected: every
 * Site and group this class creates carries its own prefixes, distinct from {@code SetupWizardIT}'s raw-SQL
 * {@code G}/{@code A} group and service, and each test class gets its own isolated Testcontainers database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, CatalogueSeedingIT.Clocks.class})
class CatalogueSeedingIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-22T09:00:00Z");

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
            return Files.createTempDirectory("qms-keys-catalogue-seeding");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired CatalogueSeedingService catalogueSeeding;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private record StaffUser(UUID id, String token) {}

    static Stream<String> shippedProfiles() {
        return Stream.of("banking", "healthcare", "producer_services", "government", "generic");
    }

    @ParameterizedTest
    @MethodSource("shippedProfiles")
    void seedsTheStarterCatalogueAndNumberingForEachShippedProfileThenIssuesATestToken(String profileId) throws Exception {
        clock.set(BASE);
        StaffUser admin = admin(null);
        UUID site = createSite("Site " + profileId);

        assertThat(status(resetProfile(admin, profileId))).isEqualTo(200);

        MvcResult seeded = seedCatalogue(admin, site);
        assertThat(status(seeded)).as(body(seeded)).isEqualTo(200);
        UUID groupId = UUID.fromString(str(seeded, "$.service_group_id"));
        List<String> createdKinds = field(seeded, "$.created[*].kind");
        assertThat(createdKinds).contains("service_group", "service");
        assertThat((List<?>) field(seeded, "$.skipped")).isEmpty();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_group WHERE id = ? AND site_id = ?", Integer.class, groupId, site)).isEqualTo(1);

        List<String> prefixes = jdbc.queryForList(
                "SELECT token_prefix FROM service WHERE service_group_id = ? ORDER BY token_prefix", String.class, groupId);
        assertThat(prefixes).isNotEmpty();

        Map<String, Object> rule = jdbc.queryForMap(
                "SELECT prefix_source, padding, sequence_start, reset_boundary FROM numbering_rule WHERE scope_type = 'service_group' AND scope_id = ?", groupId);
        assertThat(rule.get("prefix_source")).isEqualTo("service");
        assertThat(((Number) rule.get("padding")).intValue()).isEqualTo(3);
        assertThat(rule.get("reset_boundary")).isEqualTo("daily");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'profile.catalogue_seeded' AND entity_id = ?", Integer.class, groupId))
                .isEqualTo(1);

        UUID firstServiceId = jdbc.queryForObject(
                "SELECT id FROM service WHERE service_group_id = ? ORDER BY token_prefix LIMIT 1", UUID.class, groupId);
        String firstPrefix = jdbc.queryForObject("SELECT token_prefix FROM service WHERE id = ?", String.class, firstServiceId);

        MvcResult issued = mvc.perform(post("/api/v1/setup/test-token")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + firstServiceId + "\"}"))
                .andReturn();
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        String tokenNumber = str(issued, "$.token_number");
        assertThat(tokenNumber).isEqualTo(firstPrefix + "-001");
    }

    @Test
    void aSecondSeedCreatesNothingNew() throws Exception {
        clock.set(BASE);
        StaffUser admin = admin(null);
        UUID site = createSite("Second seed site");
        assertThat(status(resetProfile(admin, "banking"))).isEqualTo(200);

        MvcResult first = seedCatalogue(admin, site);
        assertThat(status(first)).as(body(first)).isEqualTo(200);
        assertThat((List<?>) field(first, "$.created")).isNotEmpty();

        MvcResult second = seedCatalogue(admin, site);
        assertThat(status(second)).as(body(second)).isEqualTo(200);
        assertThat((List<?>) field(second, "$.created")).isEmpty();
        List<String> secondReasons = field(second, "$.skipped[*].reason");
        assertThat(secondReasons).isNotEmpty().allMatch("already_exists"::equals);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_group WHERE site_id = ?", Integer.class, site)).isEqualTo(1);
        Integer serviceCount = jdbc.queryForObject(
                "SELECT count(*) FROM service v JOIN service_group g ON g.id = v.service_group_id WHERE g.site_id = ?", Integer.class, site);
        assertThat(serviceCount).isEqualTo(6); // banking ships six starter services (Cash deposit A .. Remittance F)
    }

    @Test
    void aStarterServiceWhoseTokenPrefixIsAlreadyUsedIsSkippedAndReportedNeverRenamed() throws Exception {
        clock.set(BASE);
        StaffUser admin = admin(null);
        UUID site = createSite("Prefix clash site");
        assertThat(status(resetProfile(admin, "banking"))).isEqualTo(200);

        // A service already at the Site, created by hand before seeding, that happens to hold banking's own
        // "Cash deposit" prefix ("A").
        UUID existingGroup = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Existing group\"}'::jsonb, 'X')",
                existingGroup, site);
        UUID existingService = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Pre-existing\"}'::jsonb, 'A', 10, 30, '[\"reception\"]'::jsonb, 'both')",
                existingService, existingGroup);

        MvcResult seeded = seedCatalogue(admin, site);
        assertThat(status(seeded)).as(body(seeded)).isEqualTo(200);

        List<Map<String, Object>> clashes = JsonPath.read(body(seeded), "$.skipped[?(@.reason=='prefix_in_use')]");
        assertThat(clashes).hasSize(1);
        assertThat(clashes.get(0).get("name")).isEqualTo("Cash deposit");
        assertThat(clashes.get(0).get("kind")).isEqualTo("service");

        // The other five starter services (B..F) and the new group are still created; only the clashing one is skipped.
        List<String> createdKinds = field(seeded, "$.created[*].kind");
        assertThat(createdKinds).hasSize(6);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM service v JOIN service_group g ON g.id = v.service_group_id"
                                + " WHERE g.site_id = ? AND v.name_i18n->>'en' = 'Cash deposit'",
                        Integer.class, site))
                .as("the clashing starter service was never created under the seeded group")
                .isEqualTo(0);
    }

    @Test
    void anInactiveSiteIsRefusedWithParentInactive() throws Exception {
        clock.set(BASE);
        StaffUser admin = admin(null);
        UUID site = createSite("Inactive site");
        jdbc.update("UPDATE site SET active = false WHERE id = ?", site);

        MvcResult seeded = seedCatalogue(admin, site);
        assertThat(status(seeded)).as(body(seeded)).isEqualTo(409);
        assertThat(str(seeded, "$.error.details.reason")).isEqualTo("parent_inactive");
    }

    @Test
    void aSiteOutsideTheCallersScopeIsForbidden() throws Exception {
        clock.set(BASE);
        UUID inScope = createSite("In scope");
        UUID outOfScope = createSite("Out of scope");
        StaffUser scopedAdmin = admin(inScope);

        MvcResult seeded = seedCatalogue(scopedAdmin, outOfScope);
        assertThat(status(seeded)).isEqualTo(403);
    }

    /** FR-CFG-108/API-016: the endpoint needs both permissions together, re-checked here at the service layer, the
     * same way {@code AdminApiIT#permissionsAreEnforcedAtTheServiceLayerNotOnlyTheControllers} checks user admin. No
     * shipped role carries only one of {@code config:org_sites_zones}/{@code config:service_catalogue} (both are
     * Y/Y for system_admin and org_admin, "-"/"-" for everyone else in the SRS §5.2 matrix), so a caller with just
     * one is fabricated directly rather than through a real role assignment. */
    @Test
    void refusesACallerWithOnlyOneOfTheTwoRequiredPermissions() {
        UUID siteId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(authenticationWith(Authorities.CONFIG_ORG_SITES_ZONES));
        assertThatThrownBy(() -> catalogueSeeding.seedStarter(siteId)).isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.getContext().setAuthentication(authenticationWith(Authorities.CONFIG_SERVICE_CATALOGUE));
        assertThatThrownBy(() -> catalogueSeeding.seedStarter(siteId)).isInstanceOf(AccessDeniedException.class);
    }

    private static JwtAuthenticationToken authenticationWith(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(UUID.randomUUID().toString()).claim("roles", List.of("org_admin")).build();
        var granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        return new JwtAuthenticationToken(jwt, granted);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private UUID createSite(String name) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, ?, ?, 'Asia/Dhaka', '1 Main Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, name, "S-" + site.toString().substring(0, 8));
        return site;
    }

    /** A system_admin, scoped to one Site when given, unrestricted (organisation-wide) otherwise. */
    private StaffUser admin(UUID scopedToSite) throws Exception {
        UUID id = UUID.randomUUID();
        String username = "admin-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, 'en')",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), username);
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, Role.SYSTEM_ADMIN.wire());
            ps.setArray(4, connection.createArrayOf("uuid", scopedToSite == null ? new UUID[0] : new UUID[] {scopedToSite}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        // Login's access token is time-checked against the real wall clock (JwtTimestampValidator has no seam for
        // the injected business Clock), so the clock must sit near real time for the login call itself, the same
        // guard SetupWizardIT's own user() helper uses.
        Instant businessTime = clock.instant();
        clock.set(Instant.now());
        MvcResult result;
        try {
            result = mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
        } finally {
            clock.set(businessTime);
        }
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        return new StaffUser(id, field(result, "$.access_token"));
    }

    private MvcResult resetProfile(StaffUser admin, String profileId) throws Exception {
        return call(post("/api/v1/setup/profile/reset"), admin.token(), "{\"profile_id\":\"" + profileId + "\"}");
    }

    private MvcResult seedCatalogue(StaffUser admin, UUID siteId) throws Exception {
        return call(post("/api/v1/setup/seed-catalogue"), admin.token(), "{\"site_id\":\"" + siteId + "\"}");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header("Authorization", "Bearer " + token);
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

    private static String str(MvcResult result, String path) throws Exception {
        Object value = field(result, path);
        return value == null ? null : value.toString();
    }
}
