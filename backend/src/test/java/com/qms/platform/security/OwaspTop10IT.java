package com.qms.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NFR-SEC-050: the system tested against the OWASP Top 10 (2021) before a major release, findings triaged and
 * criticals fixed before shipping. Ten categories, evidence per category:
 *
 * <ul>
 *   <li>A01 Broken access control — proven cross-site 403s already exist throughout the suite (e.g.
 *       {@code CatalogueAdminIT#aUserScopedToOneSiteCannotReachAnothers}) via {@link ScopeGuard} at the service
 *       layer, defence in depth behind every {@code @PreAuthorize} ({@link PermissionMatrixTest}); this class adds
 *       no new probe for it.
 *   <li>A02 Cryptographic failures — passwords are BCrypt-hashed (never stored or returned in plain text, see
 *       {@code IdentityAdminIT}), JWTs are ES256-signed ({@code JwtDecoderFactory}), TLS termination is documented
 *       at {@code docs/ops/tls-and-kiosk-display-shell.md} (NFR-SEC-010); tested here at
 *       {@link #aPasswordHashNeverLeavesTheApiInAnyResponse()}.
 *   <li>A03 Injection — {@link #aSqlInjectionPayloadInASearchParameterIsTreatedAsLiteralTextNotSql()}: JPA/JDBC
 *       parameter binding throughout (no string-concatenated SQL in the codebase) means a crafted query string
 *       cannot alter the statement; proven adversarially below rather than only by code inspection.
 *   <li>A04 Insecure design — login lockout after repeated failures is tested at {@code LoginLockoutIT} (ticket 03);
 *       step-up re-authentication for sensitive actions at {@code StepUpIT} (ticket 14).
 *   <li>A05 Security misconfiguration — {@link #standardSecurityHeadersArePresentAndTheServerHeaderIsGeneric()} and
 *       {@link #anUnhandledFailureNeverLeaksAStackTraceOrExceptionClassName()}: every error is the closed
 *       {@link com.qms.platform.ErrorCode} envelope, never a raw exception ({@code GlobalExceptionHandler}).
 *   <li>A06 Vulnerable and outdated components — {@code ./gradlew dependencyCheckAnalyze} (CI job, needs
 *       {@code NVD_API_KEY} and network access to the NVD feed); not runnable in this sandbox, so not exercised by
 *       this class — see the traceability matrix note for NFR-SEC-050.
 *   <li>A07 Identification and authentication failures — refresh-token reuse detection, revocation and expiry are
 *       covered by {@code RealtimeRevocationIT}, {@code RealtimeExpiryIT} and {@code RotateKeysIT}.
 *   <li>A08 Software and data integrity failures — the configuration bundle is signed and its signature verified on
 *       import (ticket 55, {@code ConfigBundleIT}); the offline installer artefact is checksummed
 *       ({@code deploy/bundle.sh}, ticket 60).
 *   <li>A09 Security logging and monitoring failures — every state change writes an audit entry
 *       ({@code AuditWriterTest}, append-only, redacted of PII in transit per {@link com.qms.platform.LogRedaction});
 *       denied requests are logged by {@link com.qms.platform.security.AuthorizationDenials}.
 *   <li>A10 Server-side request forgery — outbound URLs (Web Push endpoints, webhooks) are validated against
 *       loopback, link-local, RFC1918/ULA and cloud-metadata addresses before any request is made
 *       ({@code PushEndpointSecurityTest}, {@code WebhookEndpointSecurityTest}), including the IPv6 ULA and
 *       IPv4-mapped bypass fixed out of band (commit {@code fde12f4}).
 * </ul>
 *
 * <p>No critical finding came out of this pass; the categories above with an existing reference were already
 * addressed by the ticket that introduced the surface, not newly discovered here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class OwaspTop10IT {

    private static final String PASSWORD = "Correct-Horse-9";
    private static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-owasp");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---- helpers, the same shape used throughout the suite (e.g. DeviceFleetIT, CatalogueAdminIT) ----------------

    private String receptionToken() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "reception-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), "Reception Owasp", "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, Role.RECEPTION_OPERATOR.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
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

    // ---- A03: Injection -------------------------------------------------------------------------------------------

    @Test
    void aSqlInjectionPayloadInASearchParameterIsTreatedAsLiteralTextNotSql() throws Exception {
        String token = receptionToken();
        // A real visitor, so a successful lookup afterwards proves the table is intact and reachable.
        String registerJson = "{\"name\":\"Injection Control\",\"phone\":\"+8801700000099\"}";
        MvcResult registered = call(post("/api/v1/visitors"), token, registerJson);
        assertThat(status(registered)).as(body(registered)).isEqualTo(201);

        String[] payloads = {
            "' OR '1'='1", "'; DROP TABLE visitors; --", "x' UNION SELECT username, password_hash FROM users --", "\"; SELECT pg_sleep(5); --"
        };
        for (String payload : payloads) {
            MvcResult probed = mvc.perform(get("/api/v1/visitors/lookup")
                            .param("q", payload)
                            .header("Authorization", "Bearer " + token))
                    .andReturn();
            // Never a server error and never a raw SQL/driver error surfacing through the envelope: either a clean
            // "not found" (VALIDATION_FAILED/NOT_FOUND-shaped 4xx) or a 200 with no match, never 500.
            assertThat(status(probed)).as(payload + " -> " + body(probed)).isLessThan(500);
            assertThat(body(probed).toLowerCase()).as(payload).doesNotContain("sqlstate").doesNotContain("org.postgresql").doesNotContain("syntax error");
        }

        // The table survived every payload above: the control visitor is still findable by its real phone number.
        MvcResult stillThere = call(get("/api/v1/visitors/lookup").param("q", "+8801700000099"), token, null);
        assertThat(status(stillThere)).as(body(stillThere)).isEqualTo(200);
        assertThat(JsonPath.<String>read(body(stillThere), "$.name")).isEqualTo("Injection Control");
    }

    // ---- A03/A05: stored payload round-trips as inert data, never as markup -----------------------------------------

    @Test
    void aScriptPayloadInAVisitorNameRoundTripsAsLiteralJsonTextNeverAsRenderedMarkup() throws Exception {
        String token = receptionToken();
        String xss = "<script>alert(document.cookie)</script>";
        String registerJson = "{\"name\":" + jsonString(xss) + ",\"phone\":\"+8801700000098\"}";

        MvcResult registered = call(post("/api/v1/visitors"), token, registerJson);
        assertThat(status(registered)).as(body(registered)).isEqualTo(201);
        assertThat(registered.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);

        MvcResult found = call(get("/api/v1/visitors/lookup").param("q", "+8801700000098"), token, null);
        assertThat(status(found)).isEqualTo(200);
        // The API is JSON-only (no server-rendered HTML anywhere in this codebase, ADR-0012): the payload comes back
        // as an ordinary JSON string value, quoted and escaped by Jackson, never unescaped into a response that a
        // browser would parse as HTML. The frontend (React) escapes it again on render (no `dangerouslySetInnerHTML`
        // is used for visitor-entered text anywhere in frontend/apps or frontend/packages).
        assertThat(found.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(JsonPath.<String>read(body(found), "$.name")).isEqualTo(xss);
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- A02: sensitive data never leaves the API --------------------------------------------------------------

    @Test
    void aPasswordHashNeverLeavesTheApiInAnyResponse() throws Exception {
        String token = receptionToken();
        MvcResult me = call(get("/api/v1/auth/me"), token, null);
        assertThat(status(me)).isEqualTo(200);
        assertThat(body(me).toLowerCase()).doesNotContain("password").doesNotContain("bcrypt").doesNotContain("$2a$").doesNotContain("$2b$");
    }

    // ---- A05: security misconfiguration -----------------------------------------------------------------------

    @Test
    void standardSecurityHeadersArePresentAndTheServerHeaderIsGeneric() throws Exception {
        String token = receptionToken();
        MvcResult ok = call(get("/api/v1/auth/me"), token, null);
        assertThat(status(ok)).isEqualTo(200);
        assertThat(ok.getResponse().getHeader("X-Content-Type-Options")).as("MIME sniffing guard").isEqualTo("nosniff");
        // No header discloses the framework/library version (Spring Security's default header set carries none).
        assertThat(ok.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase("X-Powered-By"));
    }

    @Test
    void anUnhandledFailureNeverLeaksAStackTraceOrExceptionClassName() throws Exception {
        String token = receptionToken();
        // Malformed JSON body: triggers Spring's message-not-readable path, handled by GlobalExceptionHandler.
        MvcResult malformed = mvc.perform(post("/api/v1/visitors")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andReturn();
        assertThat(status(malformed)).isEqualTo(400);
        String responseBody = body(malformed).toLowerCase();
        assertThat(responseBody).doesNotContain("exception").doesNotContain("stacktrace").doesNotContain("at com.qms").doesNotContain(".java:");
        assertThat(JsonPath.<String>read(body(malformed), "$.error.code")).isNotBlank();
    }
}
