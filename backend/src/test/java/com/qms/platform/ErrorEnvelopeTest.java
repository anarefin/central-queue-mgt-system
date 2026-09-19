package com.qms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ErrorEnvelopeTest.Probe.class)
@Import({WebSliceTestConfig.class, ErrorEnvelopeTest.Probe.class})
class ErrorEnvelopeTest {

    @RestController
    static class Probe {
        record Body(@NotBlank String name) {}

        @GetMapping("/probe/api-error")
        void apiError() {
            throw new ApiException(ErrorCode.CONFLICT, Map.of("k", "v"));
        }

        @GetMapping("/probe/rate-limited")
        void rateLimited() {
            throw new ApiException(ErrorCode.RATE_LIMITED, Map.of("reason", "rate_limited", "retry_after_seconds", 42L));
        }

        @GetMapping("/probe/written-message")
        void writtenMessage() {
            throw new ApiException(
                    ErrorCode.SERVICE_CLOSED,
                    "issuance.refused.cap_reached",
                    new Object[0],
                    Map.of("reason", "cap_reached"),
                    Map.of("bn", "আজকের টোকেন শেষ"));
        }

        @PostMapping("/probe/validate")
        void validate(@Valid @RequestBody Body body) {}

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }

    private static final Set<String> CLOSED_SET =
            Arrays.stream(ErrorCode.values()).map(ErrorCode::wire).collect(Collectors.toSet());

    @Autowired MockMvc mvc;

    @Test
    void basePathIsApiV1() throws Exception {
        mvc.perform(get("/probe/api-error")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/probe/api-error")).andExpect(status().isConflict());
    }

    @Test
    void apiExceptionUsesEnvelopeWithCodeDetailsAndTraceId() throws Exception {
        var result = mvc.perform(get("/api/v1/probe/api-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(jsonPath("$.error.details.k").value("v"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.message_i18n.en").isNotEmpty())
                .andExpect(jsonPath("$.error.message_i18n.bn").isNotEmpty())
                .andReturn();

        String traceId = JsonPath.read(result.getResponse().getContentAsString(), "$.error.trace_id");
        assertThat(traceId).isNotBlank();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(traceId);
    }

    @Test
    void aRateLimitedAnswerSaysWhenToTryAgainInRetryAfter() throws Exception {
        // API-090: 429 with Retry-After.
        mvc.perform(get("/api/v1/probe/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andExpect(jsonPath("$.error.details.retry_after_seconds").value(42));
    }

    @Test
    void aMessageAnAdministratorWroteReplacesTheBuiltInTextOnlyForItsLanguage() throws Exception {
        // FR-CFG-023: the cap message is configurable; a language without one gets the built-in sentence (FR-I18N-011).
        mvc.perform(get("/api/v1/probe/written-message").header("Accept-Language", "bn"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("service_closed"))
                .andExpect(jsonPath("$.error.message").value("আজকের টোকেন শেষ"))
                .andExpect(jsonPath("$.error.message_i18n.bn").value("আজকের টোকেন শেষ"))
                .andExpect(jsonPath("$.error.message_i18n.en").value("The limit of tickets for this service today has been reached."));
        mvc.perform(get("/api/v1/probe/written-message").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.error.message").value("The limit of tickets for this service today has been reached."));
    }

    @Test
    void unknownPathIsNotFoundEnvelope() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void wrongMethodIsMethodNotAllowedEnvelope() throws Exception {
        mvc.perform(post("/api/v1/probe/api-error"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"));
    }

    @Test
    void invalidFieldIsValidationFailedWithFieldDetails() throws Exception {
        mvc.perform(post("/api/v1/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("name"));
    }

    @Test
    void malformedJsonIsValidationFailed() throws Exception {
        mvc.perform(post("/api/v1/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void unsupportedMediaTypeIsItsOwnCode() throws Exception {
        mvc.perform(post("/api/v1/probe/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
    }

    @Test
    void unexpectedExceptionNeverLeaksItsMessage() throws Exception {
        var body = mvc.perform(get("/api/v1/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("secret internal detail").doesNotContain("IllegalStateException");
    }

    @Test
    void everyCodeEmittedComesFromTheClosedSet() throws Exception {
        for (String path : new String[] {"/api/v1/nope", "/api/v1/probe/api-error", "/api/v1/probe/boom"}) {
            String json = mvc.perform(get(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(CLOSED_SET).contains(JsonPath.<String>read(json, "$.error.code"));
        }
    }

    @Test
    void acceptLanguageSelectsTheMessageLanguageAndBodyIsUtf8() throws Exception {
        String bangla = mvc.perform(get("/api/v1/nope").header("Accept-Language", "bn"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String english = mvc.perform(get("/api/v1/nope").header("Accept-Language", "en"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String banglaMessage = JsonPath.read(bangla, "$.error.message");
        String englishMessage = JsonPath.read(english, "$.error.message");
        assertThat(banglaMessage).isEqualTo(JsonPath.<String>read(bangla, "$.error.message_i18n.bn"));
        assertThat(englishMessage).isEqualTo(JsonPath.<String>read(english, "$.error.message_i18n.en"));
        assertThat(banglaMessage).isNotEqualTo(englishMessage).matches(".*[\\u0980-\\u09FF].*");
    }

    @Test
    void unsupportedAcceptLanguageFallsBackToDefault() throws Exception {
        String json = mvc.perform(get("/api/v1/nope").header("Accept-Language", "fr"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonPath.<String>read(json, "$.error.message"))
                .isEqualTo(JsonPath.<String>read(json, "$.error.message_i18n.en"));
    }

    @Test
    void wellFormedUpstreamTraceIdIsKeptAndMaliciousOneReplaced() throws Exception {
        mvc.perform(get("/api/v1/nope").header("X-Trace-Id", "018f0000-aaaa-7bbb-8ccc-123456789abc"))
                .andExpect(header().string("X-Trace-Id", "018f0000-aaaa-7bbb-8ccc-123456789abc"));

        String replaced = mvc.perform(get("/api/v1/nope").header("X-Trace-Id", "bad id\"}{"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");
        assertThat(replaced).matches("[0-9a-f-]{36}");
    }
}
