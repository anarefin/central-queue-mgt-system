package com.qms.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Per-installation ES256 signing keys (API-015, NFR-SEC-013), kept as owner-only files in {@code keyDir}, never in
 * source control or the database. The first start generates a key; {@link #rotate()} adds a new active key and retires
 * the previous one, whose public half keeps validating tokens for the configured overlap window. A running node
 * re-reads the directory every {@code keyRefreshInterval}, so a rotation done by another process is picked up.
 */
public class SigningKeyStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIR = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityProperties properties;
    private final Clock clock;
    private final Path dir;
    private volatile List<SigningKey> keys = List.of();
    private volatile Instant loadedAt = Instant.MIN;

    public SigningKeyStore(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.dir = properties.keyDir().toAbsolutePath();
        createDirectory();
        reload();
        if (activeOrNull() == null) {
            generate();
            reload();
        }
    }

    /** The key new tokens are signed with: the newest one that has not been retired. */
    SigningKey activeKey() {
        refreshIfStale();
        SigningKey active = activeOrNull();
        if (active == null) {
            throw new IllegalStateException("No active signing key in " + dir);
        }
        return active;
    }

    /** Public keys that may validate a token now: every active key plus retired keys still inside the overlap. */
    List<ECKey> verificationKeys() {
        refreshIfStale();
        Instant now = clock.instant();
        return keys.stream()
                .filter(k -> k.isActive() || k.retiredAt().plus(properties.keyRotationOverlap()).isAfter(now))
                .map(k -> k.jwk().toPublicJWK())
                .toList();
    }

    /** Generates a new active key and retires every previous one. Old tokens keep validating for the overlap window. */
    public synchronized void rotate() {
        reload();
        List<SigningKey> previous = keys.stream().filter(SigningKey::isActive).toList();
        generate();
        Instant now = clock.instant();
        for (SigningKey key : previous) {
            write(retiredMarker(key.kid()), now.toString());
        }
        reload();
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private SigningKey activeOrNull() {
        return keys.stream()
                .filter(SigningKey::isActive)
                .max(Comparator.comparing(SigningKey::createdAt).thenComparing(SigningKey::kid))
                .orElse(null);
    }

    private void refreshIfStale() {
        if (clock.instant().isAfter(loadedAt.plus(properties.keyRefreshInterval()))) {
            reload();
        }
    }

    private synchronized void reload() {
        List<SigningKey> loaded = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jwk")) {
            for (Path file : files) {
                ECKey jwk = ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
                String kid = jwk.getKeyID();
                Instant created = jwk.getIssueTime() == null ? Files.getLastModifiedTime(file).toInstant() : jwk.getIssueTime().toInstant();
                loaded.add(new SigningKey(kid, jwk, created, readRetired(kid)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read signing keys from " + dir, e);
        } catch (ParseException e) {
            throw new IllegalStateException("Corrupt signing key file in " + dir, e);
        }
        this.keys = List.copyOf(loaded);
        this.loadedAt = clock.instant();
    }

    private Instant readRetired(String kid) throws IOException {
        Path marker = retiredMarker(kid);
        return Files.exists(marker) ? Instant.parse(Files.readString(marker, StandardCharsets.UTF_8).trim()) : null;
    }

    private void generate() {
        try {
            String kid = "k-" + clock.instant().toEpochMilli() + "-" + HexFormat.of().formatHex(randomBytes(4));
            ECKey key = new ECKeyGenerator(Curve.P_256)
                    .keyID(kid)
                    .issueTime(Date.from(clock.instant()))
                    .generate();
            write(dir.resolve(kid + ".jwk"), key.toJSONString());
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate signing key", e);
        }
    }

    private Path retiredMarker(String kid) {
        return dir.resolve(kid + ".retired");
    }

    /** Creates the file with owner-only permissions from the start, so the private key is never briefly readable. */
    private void write(Path file, String content) {
        try {
            try {
                Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (FileAlreadyExistsException exists) {
                // overwriting a marker is fine; permissions were set when it was created
            } catch (UnsupportedOperationException notPosix) {
                Files.createFile(file);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private void createDirectory() {
        try {
            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_DIR));
                } catch (UnsupportedOperationException notPosix) {
                    Files.createDirectories(dir);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create key directory " + dir, e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
