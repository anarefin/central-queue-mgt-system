package com.qms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.ECKey;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** API-015: {@code --spring.profiles.active=rotate-keys} adds a new active key and retires the old one, then exits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("rotate-keys")
class RotateKeysIT {

    static final Path KEYS = keyDir();
    static final String KID_BEFORE = seedKey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEYS::toString);
    }

    private static Path keyDir() {
        try {
            return Files.createTempDirectory("qms-keys-rotate");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An installation that already has a key, created before the app starts. */
    private static String seedKey() {
        return new SigningKeyStore(SecurityProperties.forKeyDir(KEYS), Clock.systemUTC()).activeKey().kid();
    }

    @Autowired SigningKeyStore keys;

    @Test
    void theRunnerRotatedTheKeyAndRetiredTheOldOne() throws IOException {
        assertThat(keys.activeKey().kid()).isNotEqualTo(KID_BEFORE);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(KEYS, "*.jwk")) {
            assertThat(files).hasSize(2);
        }
        assertThat(Files.exists(KEYS.resolve(KID_BEFORE + ".retired"))).isTrue();
        assertThat(keys.verificationKeys()).extracting(ECKey::getKeyID).contains(KID_BEFORE, keys.activeKey().kid());
    }
}
