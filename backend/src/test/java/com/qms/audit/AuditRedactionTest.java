package com.qms.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Audit entries must never become a second place secrets leak: before/after values are scrubbed on the way in. */
class AuditRedactionTest {

    @Test
    void secretLikeKeysAreMaskedAtAnyDepthAndCase() {
        Map<String, Object> input = Map.of(
                "username", "rahim",
                "Password", "hunter2",
                "nested", Map.of("password_hash", "$2a$12$abc", "keep", 1),
                "list", List.of(Map.of("refresh_token", "r.t", "ok", "yes")),
                "otp", "123456",
                "authorization", "Bearer x.y.z");

        Map<String, Object> out = AuditRedaction.scrub(input);

        assertThat(out.get("username")).isEqualTo("rahim");
        assertThat(out.get("Password")).isEqualTo("[redacted]");
        assertThat(out.get("otp")).isEqualTo("[redacted]");
        assertThat(out.get("authorization")).isEqualTo("[redacted]");
        assertThat(asMap(out.get("nested"))).containsEntry("password_hash", "[redacted]").containsEntry("keep", 1);
        assertThat((List<?>) out.get("list")).singleElement().satisfies(item ->
                assertThat(asMap(item)).containsEntry("refresh_token", "[redacted]").containsEntry("ok", "yes"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void nullStaysNull() {
        assertThat(AuditRedaction.scrub(null)).isNull();
    }
}
