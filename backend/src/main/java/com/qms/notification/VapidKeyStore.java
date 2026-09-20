package com.qms.notification;

import com.qms.platform.Profiles;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.Base64;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The per-installation VAPID key pair (§14.1, RFC 8292, NFR-SEC-013): generated once on first start and kept as an
 * owner-only file in {@code keyDir}, never in source control or a database row. It identifies this server to a push
 * service in the VAPID {@code Authorization} header ({@link VapidAuthorization}) and is the {@code
 * applicationServerKey} a browser's own {@code pushManager.subscribe()} needs. It is never used to encrypt a
 * message's own payload: that uses a fresh ephemeral key per message instead ({@link WebPushEncryption}, RFC 8291's
 * own forward secrecy), the same separation {@code SigningKeyStore} keeps between "who signed this" and "what was
 * sent". Unlike the staff token's signing keys, this one is never rotated: every subscription a browser already
 * holds names this key as its {@code applicationServerKey}, and a push service refuses a later send whose VAPID key
 * does not match the one the subscription was created against, so rotating it would silently break every visitor's
 * existing subscription.
 */
@Component
@Profile(Profiles.SERVING)
class VapidKeyStore {

    private static final String FILE_NAME = "vapid.jwk";
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIR =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final ECKey key;

    VapidKeyStore(WebPushProperties properties) {
        this.key = loadOrGenerate(properties.keyDir().toAbsolutePath());
    }

    ECPrivateKey privateKey() {
        try {
            return key.toECPrivateKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("Corrupt VAPID key", e);
        }
    }

    ECPublicKey publicKey() {
        try {
            return key.toECPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("Corrupt VAPID key", e);
        }
    }

    /**
     * The uncompressed public key point ({@code 0x04 || X || Y}, 65 bytes for P-256), base64url with no padding:
     * both the browser's own {@code applicationServerKey} and the {@code k} parameter of the VAPID Authorization
     * header (RFC 8292).
     */
    String publicKeyBase64Url() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(WebPushEncryption.encodePublicKey(publicKey()));
    }

    private static ECKey loadOrGenerate(Path dir) {
        try {
            createDirectory(dir);
            Path file = dir.resolve(FILE_NAME);
            if (Files.exists(file)) return ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            ECKey generated = new ECKeyGenerator(Curve.P_256).keyID("vapid").generate();
            try {
                writeNewFile(file, generated.toJSONString());
                return generated;
            } catch (FileAlreadyExistsException raced) {
                // Another node or process won the race to create the first key; use its key, not a second one, so
                // every node in the cluster signs with the same VAPID identity (RFC 8292's own point of the header).
                return ECKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create the VAPID key in " + dir, e);
        } catch (ParseException e) {
            throw new IllegalStateException("Corrupt VAPID key file in " + dir, e);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate the VAPID key", e);
        }
    }

    private static void createDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) return;
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
        } catch (UnsupportedOperationException notPosix) {
            Files.createDirectories(dir);
        }
    }

    private static void writeNewFile(Path file, String content) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
