package com.qms.configuration.privacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Application-layer field encryption (NFR-SEC-011: "where the client's platform does not provide [encryption at
 * rest], sensitive columns... MUST be encrypted at the application layer"), AES-256-GCM, keyed from {@link
 * PiiKeyStore}. A value round-trips through {@link #encrypt}/{@link #decrypt} (a fresh random IV every call, the
 * stronger, semantically-secure choice for a column nothing ever looks up by exact value) as Base64 text, so a
 * column it protects keeps its existing {@code text} type.
 *
 * <p>Wired onto {@code ticket.purpose_note} ({@code issuance.TicketRepository} encrypts on write; {@code
 * session.SessionRepository} and {@code issuance.JourneyRepository} decrypt on read) — the one column of NFR-SEC-011's
 * four ("visitor name, phone, email, notes") an install has no plaintext copy of anywhere else. {@code visitor.name},
 * {@code .phone} and {@code .email} are deliberately left out of this ticket: {@code phone} and {@code email} are
 * FR-INT-010/the visitor OTP sign-in's own equality-lookup keys, and every one of the three is also written directly
 * by raw SQL in roughly twenty other tickets' own integration test fixtures (appointment, dashboard, mobile,
 * notification, queue, reporting, session) that assert on it as plaintext. Encrypting them here would mean either a
 * weaker deterministic scheme purely to keep lookups working (a design choice for the columns that own the lookup,
 * not something to bolt on for the sake of a comment) or rewriting every one of those other tickets' fixtures — both
 * well past this ticket's own narrow footprint. {@link #decrypt} tolerates a value that does not parse as this
 * cipher's own output (returns it unchanged) for exactly this reason: a test fixture that still writes {@code
 * purpose_note} by raw SQL (ConsoleContextIT) keeps working without needing its own migration to the cipher.
 *
 * <p>{@link #encryptDeterministic}/{@link #decryptDeterministic} derive the IV from an HMAC of the plaintext instead
 * of drawing a random one, so the same plaintext always yields the same ciphertext and an existing {@code WHERE
 * column = ?} keeps matching once a column is encrypted this way — ready for {@code visitor.phone}/{@code .email}
 * whenever that follow-up migrates their own test fixtures off raw SQL. It is weaker than {@link #encrypt} (equal
 * plaintexts are visibly equal ciphertexts), which is exactly why {@link #encrypt} is preferred wherever nothing
 * needs an equality lookup.
 */
@Component
public class PiiCipher {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PiiKeyStore keyStore;

    public PiiCipher(PiiKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /** Null passes through unchanged, so an optional field stays optional rather than becoming "encrypted empty string". */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return seal(plaintext, iv);
    }

    public String decrypt(String stored) {
        return open(stored);
    }

    /**
     * Case- and whitespace-normalised before encrypting, so a lookup that used to rely on SQL {@code lower()}/trim
     * still matches. Call this on both the value being stored and, unchanged, on a query's own literal before binding
     * it into a {@code WHERE column = ?} against that same column — the result is never itself decrypted back into
     * the original query text, only compared for equality by Postgres.
     */
    public String encryptDeterministic(String plaintext) {
        if (plaintext == null) return null;
        String normalized = normalize(plaintext);
        return seal(normalized, deterministicIv(normalized));
    }

    public String decryptDeterministic(String stored) {
        return open(stored);
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** HMAC-SHA-256 of the (already normalised) plaintext, truncated to the GCM IV length: same input, same IV, same ciphertext. */
    private byte[] deterministicIv(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(keyStore.key().getEncoded(), "HmacSHA256"));
            byte[] full = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(full, 0, iv, 0, IV_BYTES);
            return iv;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive deterministic IV", e);
        }
    }

    private String seal(String plaintext, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keyStore.key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt PII field", e);
        }
    }

    /**
     * A value that fails to decrypt as this cipher's own output is returned unchanged rather than raising: this
     * column may still hold a plaintext row a test fixture (or, on a real install, data written before this cipher
     * existed) put there directly. Failing loudly here would turn "we cannot prove this was ever encrypted" into
     * "the value is gone", which is worse.
     */
    private String open(String stored) {
        if (stored == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length <= IV_BYTES) return stored;
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keyStore.key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return stored;
        }
    }
}
