package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-installation VAPID key (ticket 39, §14.1, NFR-SEC-013): generated once, kept owner-only, and stable across restarts. */
class VapidKeyStoreTest {

    private static WebPushProperties propertiesFor(Path dir) {
        return new WebPushProperties(dir, "mailto:admin@qms.local", Duration.ofHours(12), 2_419_200, false);
    }

    @Test
    void generatesAKeyOnFirstStartAndKeepsItOnRestart(@TempDir Path dir) {
        Path keyDir = dir.resolve("vapid");
        VapidKeyStore first = new VapidKeyStore(propertiesFor(keyDir));
        String publicKeyFirst = first.publicKeyBase64Url();

        VapidKeyStore second = new VapidKeyStore(propertiesFor(keyDir));

        assertThat(second.publicKeyBase64Url()).isEqualTo(publicKeyFirst);
    }

    @Test
    void thePublicKeyIsAnUncompressedSixtyFiveByteP256Point(@TempDir Path dir) {
        VapidKeyStore store = new VapidKeyStore(propertiesFor(dir.resolve("vapid")));

        byte[] decoded = Base64.getUrlDecoder().decode(store.publicKeyBase64Url());

        assertThat(decoded).hasSize(65);
        assertThat(decoded[0]).isEqualTo((byte) 4);
    }

    @Test
    void theKeyFileIsOwnerOnly(@TempDir Path dir) throws IOException {
        Path keyDir = dir.resolve("vapid");
        new VapidKeyStore(propertiesFor(keyDir));

        Path keyFile = Files.list(keyDir).filter(p -> p.toString().endsWith(".jwk")).findFirst().orElseThrow();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
        assertThat(permissions).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }
}
