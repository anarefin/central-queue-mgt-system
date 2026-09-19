package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Ticket 23 against real PostgreSQL: visitor master-data CSV import (FR-INT-010, FR-INT-011) — the admin-set column
 * mapping, a manual upload's validation report, upsert by external code, and the scheduled folder pickup writing the
 * exact same kind of run. {@link VisitorCsvParserTest} already proves the CSV reader itself at the unit level; this
 * class proves the wiring and the database side around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class VisitorCsvImportIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-visitor-import");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitorImportService importService;
    @Autowired Clock clock;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void resetMapping() {
        jdbc.update("DELETE FROM visitor_import_mapping");
        jdbc.update("DELETE FROM visitor_import_run");
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private String token(Role role) throws Exception {
        UUID user = createUser(role.wire());
        jdbc.update(
                connection -> {
                    var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, user);
                    ps.setString(3, role.wire());
                    ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
                    ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                    return ps;
                });
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
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

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private MvcResult upload(String token, String filename, String content) throws Exception {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        String jsonBody = "{\"filename\":\"" + filename + "\",\"content\":\"" + escaped + "\"}";
        return call(post("/api/v1/visitors/import"), token, jsonBody);
    }

    // ---- column mapping (FR-INT-011) -----------------------------------------------------------------------------

    @Test
    void theDefaultMappingMatchesTheTargetFieldNamesUntilAnAdminSavesOneOfTheirOwn() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);

        MvcResult got = call(get("/api/v1/visitors/import/mapping"), admin, null);

        assertThat(status(got)).as(body(got)).isEqualTo(200);
        assertThat((String) field(got, "$.external_code_column")).isEqualTo("external_code");
        assertThat((String) field(got, "$.name_column")).isEqualTo("name");
    }

    @Test
    void anAdminCanRemapCsvHeadersThatDoNotMatchTheTargetFieldNames() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        String mappingBody = "{\"external_code_column\":\"Employee ID\",\"name_column\":\"Full Name\",\"phone_column\":\"Mobile\",\"email_column\":null,\"category_column\":null}";

        MvcResult saved = call(put("/api/v1/visitors/import/mapping"), admin, mappingBody);
        assertThat(status(saved)).as(body(saved)).isEqualTo(200);

        MvcResult got = call(get("/api/v1/visitors/import/mapping"), admin, null);
        assertThat((String) field(got, "$.external_code_column")).isEqualTo("Employee ID");
        assertThat((String) field(got, "$.phone_column")).isEqualTo("Mobile");

        MvcResult uploaded = upload(admin, "staff.csv", "Employee ID,Full Name,Mobile\nEMP-1,Nasrin Akter,01711111111\n");
        assertThat(status(uploaded)).as(body(uploaded)).isEqualTo(201);
        assertThat((Integer) field(uploaded, "$.inserted_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM visitor WHERE external_code = ?", String.class, "EMP-1")).isEqualTo("Nasrin Akter");
    }

    @Test
    void anExternalCodeColumnAndANameColumnAreRequiredToSaveAMapping() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);

        MvcResult missingExternalCode = call(put("/api/v1/visitors/import/mapping"), admin, "{\"external_code_column\":\" \",\"name_column\":\"Name\"}");

        assertThat(status(missingExternalCode)).isEqualTo(400);
        assertThat((String) field(missingExternalCode, "$.error.details.fields[0].field")).isEqualTo("external_code_column");
    }

    // ---- manual upload, validation report, upsert by external code (FR-INT-011) ----------------------------------

    @Test
    void aManualUploadInsertsNewVisitorsAndReportsTheCounts() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        String csv = "external_code,name,phone,email,category\nCSV-1,Amina Rahman,01700000101,amina@example.com,vip\nCSV-2,Karim Uddin,01700000102,,\n";

        MvcResult uploaded = upload(admin, "visitors.csv", csv);

        assertThat(status(uploaded)).as(body(uploaded)).isEqualTo(201);
        assertThat((String) field(uploaded, "$.source")).isEqualTo("manual");
        assertThat((Integer) field(uploaded, "$.total_rows")).isEqualTo(2);
        assertThat((Integer) field(uploaded, "$.inserted_count")).isEqualTo(2);
        assertThat((Integer) field(uploaded, "$.updated_count")).isEqualTo(0);
        assertThat((Integer) field(uploaded, "$.failed_count")).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT name FROM visitor WHERE external_code = ?", String.class, "CSV-1")).isEqualTo("Amina Rahman");
        assertThat(jdbc.queryForObject("SELECT category FROM visitor WHERE external_code = ?", String.class, "CSV-1")).isEqualTo("vip");
        UUID runId = UUID.fromString(field(uploaded, "$.id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'visitor.import.completed' AND entity_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    @Test
    void importingTheSameExternalCodeAgainUpdatesTheExistingVisitorInsteadOfDuplicatingIt() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        upload(admin, "first.csv", "external_code,name,phone\nCSV-U,Old Name,01700000201\n");

        MvcResult second = upload(admin, "second.csv", "external_code,name,phone\nCSV-U,New Name,01700000202\n");

        assertThat(status(second)).as(body(second)).isEqualTo(201);
        assertThat((Integer) field(second, "$.inserted_count")).isEqualTo(0);
        assertThat((Integer) field(second, "$.updated_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visitor WHERE external_code = ?", Integer.class, "CSV-U")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM visitor WHERE external_code = ?", String.class, "CSV-U")).isEqualTo("New Name");
        assertThat(jdbc.queryForObject("SELECT phone FROM visitor WHERE external_code = ?", String.class, "CSV-U")).isEqualTo("01700000202");
    }

    @Test
    void rowsMissingTheRequiredColumnsAreSkippedAndListedInTheValidationReportWhileValidRowsStillImport() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        String csv = "external_code,name,phone\nCSV-OK,Good Row,01700000301\n,No Code,01700000302\nCSV-NN,,01700000303\n";

        MvcResult uploaded = upload(admin, "mixed.csv", csv);

        assertThat(status(uploaded)).as(body(uploaded)).isEqualTo(201);
        assertThat((Integer) field(uploaded, "$.total_rows")).isEqualTo(3);
        assertThat((Integer) field(uploaded, "$.inserted_count")).isEqualTo(1);
        assertThat((Integer) field(uploaded, "$.failed_count")).isEqualTo(2);
        List<String> errorFields = JsonPath.read(body(uploaded), "$.errors[*].field");
        assertThat(errorFields).containsExactlyInAnyOrder("external_code", "name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visitor WHERE external_code = ?", Integer.class, "CSV-OK")).isEqualTo(1);
    }

    @Test
    void aCsvMissingAMappedColumnInItsHeaderIsRefusedBeforeAnyRowIsProcessed() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);

        MvcResult uploaded = upload(admin, "bad.csv", "code,full_name\nX,Y\n");

        assertThat(status(uploaded)).isEqualTo(400);
        assertThat((String) field(uploaded, "$.error.details.fields[0].field")).isEqualTo("mapping");
    }

    @Test
    void emptyContentIsValidationFailed() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);

        MvcResult uploaded = call(post("/api/v1/visitors/import"), admin, "{\"filename\":\"empty.csv\",\"content\":\"\"}");

        assertThat(status(uploaded)).isEqualTo(400);
    }

    // ---- CSV import is the second VisitorDirectory source (FR-INT-010) --------------------------------------------

    @Test
    void aCsvImportedVisitorIsFoundThroughTheSameVisitorDirectoryLookupAWalkInIs() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        upload(admin, "directory.csv", "external_code,name,phone,category\nCSV-DIR,Fatema Begum,01700000401,general\n");
        String reception = token(Role.RECEPTION_OPERATOR);

        MvcResult found = call(get("/api/v1/visitors/lookup?q=CSV-DIR"), reception, null);

        assertThat(status(found)).as(body(found)).isEqualTo(200);
        assertThat((String) field(found, "$.name")).isEqualTo("Fatema Begum");
        assertThat((String) field(found, "$.category")).isEqualTo("general");

        MvcResult foundByPhone = call(get("/api/v1/visitors/lookup?q=01700000401"), reception, null);
        assertThat(status(foundByPhone)).isEqualTo(200);
    }

    // ---- run history (FR-INT-011: "sees a validation report", including for a run nobody was present for) ---------

    @Test
    void pastRunsAreListedMostRecentFirstAndOneIsRetrievableInFull() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);
        upload(admin, "one.csv", "external_code,name\nCSV-R1,Row One\n");
        MvcResult secondUpload = upload(admin, "two.csv", "external_code,name\nCSV-R2,Row Two\n");
        UUID secondRunId = UUID.fromString(field(secondUpload, "$.id"));

        MvcResult list = call(get("/api/v1/visitors/import/runs"), admin, null);
        assertThat(status(list)).as(body(list)).isEqualTo(200);
        List<String> ids = JsonPath.read(body(list), "$.items[*].id");
        assertThat(ids).contains(secondRunId.toString());
        assertThat(ids.indexOf(secondRunId.toString())).isEqualTo(0); // most recent first

        MvcResult detail = call(get("/api/v1/visitors/import/runs/" + secondRunId), admin, null);
        assertThat(status(detail)).isEqualTo(200);
        assertThat((String) field(detail, "$.filename")).isEqualTo("two.csv");
    }

    @Test
    void anUnknownRunIdIsNotFound() throws Exception {
        String admin = token(Role.SYSTEM_ADMIN);

        MvcResult missing = call(get("/api/v1/visitors/import/runs/" + UUID.randomUUID()), admin, null);

        assertThat(status(missing)).isEqualTo(404);
    }

    // ---- permission (§5.2 has no row of its own for CSV import; visitor_pii:view is the closest fit, ticket 22) ---

    @Test
    void anAgentCannotImportOrSeeTheMappingOrRunsOnlyItsOwnRecordsScopeOfVisitorPiiViewNeverQualifies() throws Exception {
        String agent = token(Role.AGENT);

        assertThat(status(call(get("/api/v1/visitors/import/mapping"), agent, null))).isEqualTo(403);
        assertThat(status(upload(agent, "x.csv", "external_code,name\nE,N\n"))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/visitors/import/runs"), agent, null))).isEqualTo(403);
    }

    @Test
    void anOrgAdminCanImportToo() throws Exception {
        String orgAdmin = token(Role.ORG_ADMIN);

        MvcResult uploaded = upload(orgAdmin, "org.csv", "external_code,name\nCSV-ORG,Org Row\n");

        assertThat(status(uploaded)).as(body(uploaded)).isEqualTo(201);
    }

    // ---- scheduled folder pickup (FR-INT-011) ----------------------------------------------------------------------

    @Test
    void theSchedulerImportsEveryCsvFileWaitingInThePickupDirectoryAndMovesItAsideAfterward(@TempDir Path pickupDir) throws Exception {
        Files.writeString(pickupDir.resolve("batch.csv"), "external_code,name\nCSV-SCHED,Scheduled Row\n", StandardCharsets.UTF_8);
        VisitorImportScheduler scheduler = new VisitorImportScheduler(importService, pickupDir.toString(), clock);

        scheduler.tick();

        assertThat(jdbc.queryForObject("SELECT name FROM visitor WHERE external_code = ?", String.class, "CSV-SCHED")).isEqualTo("Scheduled Row");
        assertThat(Files.exists(pickupDir.resolve("batch.csv"))).isFalse();
        Path processedDir = pickupDir.resolve("processed");
        assertThat(processedDir).isDirectory();
        try (var files = Files.list(processedDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visitor_import_run WHERE source = 'scheduled' AND filename = 'batch.csv'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT triggered_by FROM visitor_import_run WHERE filename = 'batch.csv'", UUID.class)).isNull();
    }

    @Test
    void aFileThatFailsToImportIsMovedToTheFailedSubfolderInstead(@TempDir Path pickupDir) throws Exception {
        // No header row matching the default mapping's target columns at all: the whole file is refused.
        Files.writeString(pickupDir.resolve("bad.csv"), "not_a_known_column\nsomething\n", StandardCharsets.UTF_8);
        VisitorImportScheduler scheduler = new VisitorImportScheduler(importService, pickupDir.toString(), clock);

        scheduler.tick();

        assertThat(Files.exists(pickupDir.resolve("bad.csv"))).isFalse();
        assertThat(pickupDir.resolve("failed")).isDirectory();
    }

    @Test
    void aBlankPickupDirLeavesTheSchedulerDoingNothing() {
        VisitorImportScheduler scheduler = new VisitorImportScheduler(importService, "", clock);

        scheduler.tick(); // must not throw
    }
}
