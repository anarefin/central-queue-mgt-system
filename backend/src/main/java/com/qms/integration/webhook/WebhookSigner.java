package com.qms.integration.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * FR-INT-021: HMAC-SHA256 over the request body plus the timestamp it was signed at, so a receiver that has the
 * shared secret can verify both the payload and its freshness (a replayed old request carries an old timestamp).
 * The signed string is {@code "<timestamp>.<body>"}, one dot joining the two, and the signature travels as lowercase
 * hex in {@code X-QMS-Webhook-Signature} alongside the timestamp itself in {@code X-QMS-Webhook-Timestamp}
 * ({@link WebhookDeliveryWorker}) — the same shape GitHub's and Stripe's own webhook signatures use, so an
 * integrator's existing verification code needs only the field names changed.
 */
final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {}

    static String sign(String secret, long timestampEpochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signed = mac.doFinal((timestampEpochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign webhook delivery", e);
        }
    }
}
