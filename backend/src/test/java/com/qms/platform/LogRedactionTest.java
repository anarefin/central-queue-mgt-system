package com.qms.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** API-018: tokens, OTPs and ticket secrets must not appear in logs. */
class LogRedactionTest {

    private static final String JWT = "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLWJ5dGVz";

    @Test
    void authorizationHeaderValuesAreMasked() {
        assertThat(LogRedaction.redact("Authorization: Bearer " + JWT)).doesNotContain(JWT).contains("[redacted");
        assertThat(LogRedaction.redact("authorization=Basic dXNlcjpwYXNz")).doesNotContain("dXNlcjpwYXNz");
        assertThat(LogRedaction.redact("header Bearer abc.def-ghi_jkl")).doesNotContain("abc.def-ghi_jkl");
    }

    @Test
    void anyJwtShapedStringIsMaskedEvenWithoutAHeaderName() {
        assertThat(LogRedaction.redact("token was " + JWT + " here")).doesNotContain(JWT).contains("here");
    }

    @Test
    void ticketSecretRefreshTokenOtpAndPasswordAreMasked() {
        assertThat(LogRedaction.redact("X-Ticket-Secret: s3cr3t-value")).doesNotContain("s3cr3t-value");
        assertThat(LogRedaction.redact("cookie qms_refresh=AbC123_-xyz; Path=/")).doesNotContain("AbC123_-xyz");
        assertThat(LogRedaction.redact("otp=482913 sent")).doesNotContain("482913").contains("sent");
        assertThat(LogRedaction.redact("login password=hunter2 failed")).doesNotContain("hunter2").contains("failed");
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        String text = "Account locked after 5 failed sign-in attempts userId=00000000-0000-0000-0000-000000000001";
        assertThat(LogRedaction.redact(text)).isEqualTo(text);
        assertThat(LogRedaction.redact(null)).isNull();
    }
}
