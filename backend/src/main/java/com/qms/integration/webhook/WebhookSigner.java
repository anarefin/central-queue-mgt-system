package com.qms.integration.webhook;

import com.qms.platform.crypto.HmacSigner;

/**
 * FR-INT-021: HMAC-SHA256 over the request body plus the timestamp it was signed at, so a receiver that has the
 * shared secret can verify both the payload and its freshness (a replayed old request carries an old timestamp).
 * The signed string is {@code "<timestamp>.<body>"}, one dot joining the two, and the signature travels as lowercase
 * hex in {@code X-QMS-Webhook-Signature} alongside the timestamp itself in {@code X-QMS-Webhook-Timestamp}
 * ({@link WebhookDeliveryWorker}) — the same shape GitHub's and Stripe's own webhook signatures use, so an
 * integrator's existing verification code needs only the field names changed.
 */
final class WebhookSigner {

    private WebhookSigner() {}

    static String sign(String secret, long timestampEpochSeconds, String body) {
        return HmacSigner.signHex(secret, timestampEpochSeconds + "." + body);
    }
}
