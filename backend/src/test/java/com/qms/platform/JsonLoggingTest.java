package com.qms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** NFR-MNT-001: JSON logs carry the same correlation id that the client receives as trace_id. */
@WebMvcTest(controllers = JsonLoggingTest.Failing.class)
@Import({WebSliceTestConfig.class, JsonLoggingTest.Failing.class})
@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingTest {

    @RestController
    static class Failing {
        @GetMapping("/log-probe")
        void fail() {
            throw new IllegalStateException("boom");
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void logLineIsJsonAndCarriesTheResponseTraceId(CapturedOutput output) throws Exception {
        String traceId = mvc.perform(get("/api/v1/log-probe"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");

        String line = output.getAll().lines()
                .filter(l -> l.contains("Unhandled exception"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the handler to log the failure"));

        assertThat(line).startsWith("{").endsWith("}");
        assertThat(line).contains("\"trace_id\":\"" + traceId + "\"");
    }
}
