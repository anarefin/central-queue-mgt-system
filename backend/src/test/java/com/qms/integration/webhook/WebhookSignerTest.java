package com.qms.integration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** FR-INT-021: HMAC-SHA256 over the body plus the timestamp. */
class WebhookSignerTest {

    @Test
    void signsTheTimestampAndBodyJoinedByADot() throws Exception {
        String secret = "top-secret";
        long timestamp = 1_700_000_000L;
        String body = "{\"type\":\"ticket.called\"}";

        String signature = WebhookSigner.sign(secret, timestamp, body);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        assertThat(signature).isEqualTo(HexFormat.of().formatHex(expected));
    }

    @Test
    void aDifferentSecretYieldsADifferentSignature() {
        String a = WebhookSigner.sign("secret-a", 1L, "body");
        String b = WebhookSigner.sign("secret-b", 1L, "body");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void aDifferentTimestampOrBodyYieldsADifferentSignature() {
        String base = WebhookSigner.sign("secret", 1L, "body");
        assertThat(WebhookSigner.sign("secret", 2L, "body")).isNotEqualTo(base);
        assertThat(WebhookSigner.sign("secret", 1L, "different")).isNotEqualTo(base);
    }

    @Test
    void isDeterministic() {
        assertThat(WebhookSigner.sign("secret", 42L, "body")).isEqualTo(WebhookSigner.sign("secret", 42L, "body"));
    }
}
