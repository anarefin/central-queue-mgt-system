package com.qms.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared HMAC-SHA256 signing helper for bounded contexts that need to sign and verify payloads with a shared
 * secret (webhook delivery signatures, config bundle export/import signatures). Lives in {@code platform} rather
 * than any one context so it can be reused instead of re-implemented.
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSigner() {}

    /** Signs {@code message} with {@code secret}, returning the signature as lowercase hex. */
    public static String signHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot HMAC-sign message", e);
        }
    }

    /** Constant-time comparison of two hex-encoded signatures, to avoid timing side-channels on verification. */
    public static boolean constantTimeEquals(String expected, String given) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
