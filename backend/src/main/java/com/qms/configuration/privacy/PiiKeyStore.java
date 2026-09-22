package com.qms.configuration.privacy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The single AES-256 key application-layer field encryption signs with (NFR-SEC-011): a plain 32-byte key kept as an
 * owner-only file under {@code qms.security.key-dir} (the same directory and permission scheme {@code
 * com.qms.identity.SigningKeyStore} already uses for the JWT signing keys, never in source control or the database).
 * Unlike the signing keys there is exactly one of these and it is never rotated or retired here: rotating a
 * field-encryption key means re-encrypting every row it protects, which is a data migration of its own kind and out
 * of this ticket's own narrow scope.
 *
 * <p>Deliberately lazy: nothing touches the filesystem until {@link #key()} is first called, not in the constructor.
 * Every repository that carries a {@link PiiCipher} is a plain, unconditionally-scanned {@code @Repository}
 * ({@code issuance.TicketRepository} and friends have no {@code @Profile} of their own), so this bean is built even
 * while {@code migrate} profile only runs Flyway and exits — building it must never itself create a key file, the
 * same reason {@code identity.SigningKeyStore} needs {@code identity.KeyConfig}'s {@code @Profile("!migrate")} to
 * keep it out of that graph entirely. Laziness gets the same outcome without pulling every repository that now
 * depends on this one out of migrate's own object graph.
 */
@Component
class PiiKeyStore {

    private static final String FILE_NAME = "pii.key";
    private static final int KEY_BYTES = 32;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final com.qms.identity.SecurityProperties properties;
    private volatile SecretKeySpec key;

    PiiKeyStore(com.qms.identity.SecurityProperties properties) {
        this.properties = properties;
    }

    SecretKeySpec key() {
        SecretKeySpec loaded = key;
        if (loaded != null) return loaded;
        synchronized (this) {
            if (key == null) {
                Path file = properties.keyDir().toAbsolutePath().resolve(FILE_NAME);
                key = new SecretKeySpec(loadOrGenerate(file), "AES");
            }
            return key;
        }
    }

    private static byte[] loadOrGenerate(Path file) {
        try {
            if (Files.exists(file)) {
                return Base64.getDecoder().decode(Files.readString(file, StandardCharsets.UTF_8).strip());
            }
            byte[] generated = new byte[KEY_BYTES];
            RANDOM.nextBytes(generated);
            write(file, Base64.getEncoder().encodeToString(generated));
            return generated;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create PII key " + file, e);
        }
    }

    private static void write(Path file, String content) throws IOException {
        if (!Files.exists(file.getParent())) Files.createDirectories(file.getParent());
        try {
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (FileAlreadyExistsException exists) {
            // Another process won the race; its content is what we read next time.
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
