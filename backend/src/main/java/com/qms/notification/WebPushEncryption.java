package com.qms.notification;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
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

/**
 * Web Push message encryption (ticket 39, RFC 8291) over RFC 8188's {@code aes128gcm} content coding: a fresh
 * ephemeral P-256 key pair per message (forward secrecy — never the VAPID key, which only signs the Authorization
 * header, {@link VapidAuthorization}), ECDH with the subscriber's own public key, HKDF-derived content-encryption
 * key and nonce (RFC 5869), one AES-128-GCM record (the whole payload always fits in one; nothing here chunks a
 * larger plaintext into more).
 */
final class WebPushEncryption {

    /** The {@code rs} (record size) field of the aes128gcm header; must exceed the plaintext plus its 17-byte overhead. */
    private static final int RECORD_SIZE = 4096;
    private static final byte[] WEB_PUSH_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {}

    /**
     * Encrypts {@code plaintext} for one subscriber, given their uncompressed P-256 public key (65 bytes) and
     * 16-byte auth secret — exactly as delivered by a browser's own {@code PushSubscription.toJSON().keys}. Returns
     * the full {@code aes128gcm} body: header (salt, record size, the ephemeral public key as its {@code keyid}) then
     * the one ciphertext record.
     */
    static byte[] encrypt(byte[] uaPublicKeyBytes, byte[] authSecret, byte[] plaintext) {
        try {
            KeyPair ephemeral = generateKeyPair();
            ECPublicKey uaPublicKey = decodePublicKey(uaPublicKeyBytes);
            byte[] asPublicKeyBytes = encodePublicKey((ECPublicKey) ephemeral.getPublic());

            byte[] sharedSecret = ecdh((ECPrivateKey) ephemeral.getPrivate(), uaPublicKey);

            // RFC 8291 §3.3: derive the payload's own IKM from the ECDH secret, salted with the subscriber's auth secret.
            byte[] keyInfo = concat(WEB_PUSH_INFO_PREFIX, uaPublicKeyBytes, asPublicKeyBytes);
            byte[] ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);

            // RFC 8188 §2.1: a fresh random salt per record derives this message's own content-encryption key and nonce.
            byte[] salt = randomBytes(16);
            byte[] cek = hkdf(salt, ikm, CEK_INFO, 16);
            byte[] nonce = hkdf(salt, ikm, NONCE_INFO, 12);

            byte[] padded = concat(plaintext, new byte[] {2}); // delimiter 0x02: this is the only (and so the last) record, no padding.
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(padded);

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublicKeyBytes.length);
            header.put(salt);
            header.putInt(RECORD_SIZE);
            header.put((byte) asPublicKeyBytes.length);
            header.put(asPublicKeyBytes);

            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt Web Push payload", e);
        }
    }

    private static byte[] ecdh(ECPrivateKey privateKey, ECPublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    /** HKDF-Extract-then-Expand (RFC 5869 §2), truncated to {@code length}: every caller here asks for at most the
     * 32-byte hash size, so a single expansion round is always enough. */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmacSha256(salt, ikm);
        byte[] t1 = hmacSha256(prk, concat(info, new byte[] {1}));
        return Arrays.copyOf(t1, length);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** The uncompressed point form ({@code 0x04 || X || Y}), each coordinate padded to the curve's field size (32 bytes for P-256). */
    static byte[] encodePublicKey(ECPublicKey key) {
        int size = (key.getParams().getCurve().getField().getFieldSize() + 7) / 8;
        byte[] x = unsignedBytes(key.getW().getAffineX(), size);
        byte[] y = unsignedBytes(key.getW().getAffineY(), size);
        return concat(new byte[] {4}, x, y);
    }

    private static ECPublicKey decodePublicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) throw new InvalidKeyException("Expected an uncompressed P-256 point");
        int size = 32;
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + size));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1 + size, 1 + 2 * size));
        KeyFactory factory = KeyFactory.getInstance("EC");
        return (ECPublicKey) factory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
    }

    private static ECParameterSpec p256Params() throws GeneralSecurityException {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] unsignedBytes(BigInteger value, int size) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[size];
        if (raw.length >= size) {
            System.arraycopy(raw, raw.length - size, fixed, 0, size); // drop a leading zero sign byte, if any
        } else {
            System.arraycopy(raw, 0, fixed, size - raw.length, raw.length);
        }
        return fixed;
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
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
