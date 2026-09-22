package com.qms.configuration.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** NFR-SEC-011, ticket 54: application-layer field encryption, no database involved. */
class PiiCipherTest {

    private PiiCipher cipher() {
        try {
            return new PiiCipher(new PiiKeyStore(com.qms.identity.SecurityProperties.forKeyDir(Files.createTempDirectory("qms-pii-key-test"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aValueRoundTripsThroughEncryptAndDecrypt() {
        PiiCipher cipher = cipher();
        String stored = cipher.encrypt("Needs wheelchair access");
        assertThat(stored).isNotEqualTo("Needs wheelchair access");
        assertThat(cipher.decrypt(stored)).isEqualTo("Needs wheelchair access");
    }

    @Test
    void nullPassesThroughUnchanged() {
        PiiCipher cipher = cipher();
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void encryptDrawsAFreshIvEveryCallSoTheSamePlaintextNeverLooksTheSameTwice() {
        PiiCipher cipher = cipher();
        assertThat(cipher.encrypt("Karim Rahman")).isNotEqualTo(cipher.encrypt("Karim Rahman"));
    }

    @Test
    void encryptDeterministicIsStableSoAnEqualityLookupStillMatches() {
        PiiCipher cipher = cipher();
        String a = cipher.encryptDeterministic("+8801700000001");
        String b = cipher.encryptDeterministic("+8801700000001");
        assertThat(a).isEqualTo(b);
        assertThat(cipher.decryptDeterministic(a)).isEqualTo("+8801700000001");
    }

    @Test
    void encryptDeterministicNormalisesCaseAndWhitespaceLikeTheSqlLowerAndTrimItReplaces() {
        PiiCipher cipher = cipher();
        assertThat(cipher.encryptDeterministic("  Karim@Example.com ")).isEqualTo(cipher.encryptDeterministic("karim@example.com"));
    }

    @Test
    void decryptToleratesAPreExistingPlaintextValueRatherThanThrowing() {
        PiiCipher cipher = cipher();
        assertThat(cipher.decrypt("Follow-up on the scan")).isEqualTo("Follow-up on the scan");
    }

    @Test
    void aDifferentKeyCannotReadWhatThisOneWrote() throws IOException {
        PiiCipher cipher = cipher();
        String stored = cipher.encrypt("Needs a certificate");

        PiiCipher other = new PiiCipher(new PiiKeyStore(com.qms.identity.SecurityProperties.forKeyDir(Files.createTempDirectory("qms-pii-key-test-2"))));
        // A ciphertext from a different key fails to authenticate; the graceful fallback returns it unreadable
        // rather than throwing, the same tolerance a genuinely pre-existing plaintext value gets.
        assertThat(other.decrypt(stored)).isEqualTo(stored);
    }

    @Test
    void theKeyFilePersistsAcrossANewStoreOverTheSameDirectory() throws IOException {
        Path dir = Files.createTempDirectory("qms-pii-key-test-3");
        var propsA = com.qms.identity.SecurityProperties.forKeyDir(dir);
        PiiCipher first = new PiiCipher(new PiiKeyStore(propsA));
        String stored = first.encrypt("Karim Rahman");

        PiiCipher second = new PiiCipher(new PiiKeyStore(propsA));
        assertThat(second.decrypt(stored)).isEqualTo("Karim Rahman");
    }
}
