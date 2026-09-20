package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.support.PostgresContainerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Web Push adapter end to end (ticket 39, FR-INT-040, §14.1, §18.3): real RFC 8291 encryption and a real VAPID
 * Authorization header, sent over the wire (a local HTTP server standing in for a push service, no Docker beyond the
 * database this suite already needs) — delivered, a 410 revoking the subscription, and no subscription at all.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class WebPushChannelIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", () -> newTempDir("qms-keys-webpush"));
        registry.add("qms.notification.web-push.key-dir", () -> newTempDir("qms-vapid-webpush"));
        // This suite's own fake push service is plain HTTP on loopback, so it needs the test-only bypass of
        // PushEndpointSecurity's HTTPS-and-not-private-address check; no environment variable exposes this in
        // application.yml, so it can only ever be turned on by a test setting the Spring property directly.
        registry.add("qms.notification.web-push.allow-insecure-endpoints-for-tests", () -> "true");
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static String newTempDir(String prefix) {
        try {
            return java.nio.file.Files.createTempDirectory(prefix).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WebPushChannel channel;
    @Autowired PushSubscriptionRepository subscriptions;

    private HttpServer server;
    private final AtomicInteger nextStatus = new AtomicInteger(201);
    private final BlockingQueue<Recorded> received = new ArrayBlockingQueue<>(10);

    private record Recorded(String authorization, String contentEncoding, String ttl, byte[] body) {}

    @BeforeEach
    void startFakePushService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", this::handle);
        server.start();
    }

    @AfterEach
    void stopFakePushService() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Recorded(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Encoding"),
                exchange.getRequestHeaders().getFirst("TTL"),
                body));
        exchange.sendResponseHeaders(nextStatus.get(), -1);
        exchange.close();
    }

    private String endpoint(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private UUID newTicket() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'GW')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'W', 10, 30, '[\"reception\"]'::jsonb, true)",
                service, group);
        UUID visit = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, now())", visit, site);
        UUID ticket = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, visit_id, origin_channel, state, issued_at, queued_at, secret_hash)"
                        + " VALUES (?, 'W-001', 1, 'daily', ?, ?, ?, ?, 'reception', 'waiting', now(), now(), 'hash')",
                ticket, service, group, site, visit);
        return ticket;
    }

    private record BrowserKeys(KeyPair keyPair, byte[] authSecret) {}

    private BrowserKeys subscribe(UUID ticketId, String endpoint) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            byte[] authSecret = randomBytes(16);
            String p256dh = base64Url(WebPushEncryption.encodePublicKey((ECPublicKey) keyPair.getPublic()));
            String auth = base64Url(authSecret);
            subscriptions.upsert(ticketId, null, endpoint, p256dh, auth, Instant.now());
            return new BrowserKeys(keyPair, authSecret);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private NotificationMessageRepository.MessageRow messageFor(UUID ticketId) {
        return new NotificationMessageRepository.MessageRow(
                UUID.randomUUID(), "your_turn", "web_push", List.of("web_push", "in_app"), 0, "en", true, UUID.randomUUID(), null, ticketId, null,
                Map.of(), "Your turn", "Token A-042 at counter 3", "queued", 0, Instant.now(), null);
    }

    // ---- tests ---------------------------------------------------------------------------------------------------

    @Test
    void aSubscribedTicketReceivesARealVapidSignedRfc8291EncryptedPush() throws Exception {
        UUID ticket = newTicket();
        String endpoint = endpoint("/push/one");
        BrowserKeys browser = subscribe(ticket, endpoint);

        NotificationChannel.Outcome outcome = channel.send(messageFor(ticket));

        assertThat(outcome.success()).isTrue();
        Recorded request = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.contentEncoding()).isEqualTo("aes128gcm");
        assertThat(request.ttl()).isNotBlank();
        assertThat(request.authorization()).startsWith("vapid t=").contains(", k=");

        byte[] decrypted = decrypt(request.body(), (ECPrivateKey) browser.keyPair().getPrivate(),
                WebPushEncryption.encodePublicKey((ECPublicKey) browser.keyPair().getPublic()), browser.authSecret());
        String json = new String(decrypted, StandardCharsets.UTF_8);
        assertThat(json).contains("\"trigger\":\"your_turn\"").contains("Token A-042 at counter 3");
    }

    @Test
    void aTicketWithNoSubscriptionFailsWithNoSubscriptionAndNeverCallsAnyone() {
        UUID ticket = newTicket();

        NotificationChannel.Outcome outcome = channel.send(messageFor(ticket));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.providerResponse()).isEqualTo("no_subscription");
    }

    @Test
    void aGoneSubscriptionIsRevokedAndAudited() {
        UUID ticket = newTicket();
        String endpoint = endpoint("/push/gone");
        subscribe(ticket, endpoint);
        nextStatus.set(410);

        NotificationChannel.Outcome outcome = channel.send(messageFor(ticket));

        assertThat(outcome.success()).isFalse();
        Boolean revoked = jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM push_subscription WHERE endpoint = ?", Boolean.class, endpoint);
        assertThat(revoked).isTrue();
        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'notification.push_subscription_revoked' AND entity_id = ?", Integer.class, ticket);
        assertThat(auditRows).isEqualTo(1);

        // The now-revoked subscription is skipped on a later send, never retried.
        received.clear();
        NotificationChannel.Outcome again = channel.send(messageFor(ticket));
        assertThat(again.providerResponse()).isEqualTo("no_subscription");
        assertThat(received).isEmpty();
    }

    // ---- an independent decryptor, playing the browser's own part of RFC 8291 / RFC 8188 --------------------------

    private static byte[] decrypt(byte[] wire, ECPrivateKey uaPrivateKey, byte[] uaPublicBytes, byte[] authSecret) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(wire);
        byte[] salt = new byte[16];
        buffer.get(salt);
        buffer.getInt(); // record size, unused here
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

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
