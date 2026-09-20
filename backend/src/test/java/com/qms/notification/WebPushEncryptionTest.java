package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * RFC 8291 + RFC 8188's {@code aes128gcm} content coding, round-tripped: this test plays the browser's own part
 * (a P-256 key pair and a 16-byte auth secret, exactly what {@code PushSubscription.getKey(...)} would hand the
 * visitor page) and decrypts what {@link WebPushEncryption#encrypt} produces with an independent implementation of
 * the same derivation, so a mistake in one side is unlikely to also be present, identically, in the other.
 */
class WebPushEncryptionTest {

    @Test
    void encryptsSoThatTheSubscribersOwnKeysDecryptTheOriginalPlaintext() throws Exception {
        KeyPair ua = generateP256();
        byte[] uaPublicBytes = WebPushEncryption.encodePublicKey((ECPublicKey) ua.getPublic());
        byte[] authSecret = randomBytes(16);
        byte[] plaintext = "{\"trigger\":\"your_turn\",\"body\":\"Token A-042, counter 3\"}".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = WebPushEncryption.encrypt(uaPublicBytes, authSecret, plaintext);

        byte[] decrypted = decrypt(encrypted, (ECPrivateKey) ua.getPrivate(), uaPublicBytes, authSecret);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void twoMessagesToTheSameSubscriberUseDifferentEphemeralKeysAndSalts() {
        KeyPair ua = generateP256();
        byte[] uaPublicBytes = WebPushEncryption.encodePublicKey((ECPublicKey) ua.getPublic());
        byte[] authSecret = randomBytes(16);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);

        byte[] first = WebPushEncryption.encrypt(uaPublicBytes, authSecret, plaintext);
        byte[] second = WebPushEncryption.encrypt(uaPublicBytes, authSecret, plaintext);

        // Forward secrecy (RFC 8291): a fresh salt and ephemeral key pair every time, so the two wire bytes never match
        // even for identical plaintext, and the header (salt + keyid) of one never repeats in the other.
        assertThat(first).isNotEqualTo(second);
        assertThat(Arrays.copyOfRange(first, 0, 21)).isNotEqualTo(Arrays.copyOfRange(second, 0, 21));
    }

    @Test
    void aMalformedSubscriberKeyIsRejectedRatherThanSilentlyMisencrypting() {
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[] {1, 2, 3}, randomBytes(16), "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- an independent decryptor, playing the browser's own part of RFC 8291 / RFC 8188 --------------------------

    private static byte[] decrypt(byte[] wire, ECPrivateKey uaPrivateKey, byte[] uaPublicBytes, byte[] authSecret) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(wire);
        byte[] salt = new byte[16];
        buffer.get(salt);
        int recordSize = buffer.getInt();
        assertThat(recordSize).isEqualTo(4096);
        int keyIdLength = buffer.get() & 0xFF;
        byte[] asPublicBytes = new byte[keyIdLength];
        buffer.get(asPublicBytes);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        ECPublicKey asPublicKey = decodePublicKey(asPublicBytes);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(uaPrivateKey);
        agreement.doPhase(asPublicKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublicBytes, asPublicBytes);
        byte[] ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);

        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);

        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2); // the last (and only) record's delimiter
        return Arrays.copyOf(padded, padded.length - 1);
    }

    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        byte[] prk = hmacSha256(salt, ikm);
        byte[] t1 = hmacSha256(prk, concat(info, new byte[] {1}));
        return Arrays.copyOf(t1, length);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static ECPublicKey decodePublicKey(byte[] uncompressed) throws Exception {
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    private static KeyPair generateP256() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
